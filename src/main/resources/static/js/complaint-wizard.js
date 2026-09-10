/* 4-step complaint wizard: details -> pin location -> photos -> review & submit. */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/citizen/complaints/new');
        return;
    }

    var current = 1;
    var TOTAL = 4;
    var photos = [];
    var wardTimer = null;
    var map = null;
    var marker = null;

    var panes = document.querySelectorAll('.wizard-pane');
    var tabs = document.querySelectorAll('#wizardTabs .wizard-step-tab');
    var backBtn = document.getElementById('wzBack');
    var nextBtn = document.getElementById('wzNext');
    var submitBtn = document.getElementById('wzSubmit');
    var feedback = document.getElementById('wzFeedback');
    var form = document.getElementById('complaintWizard');

    function show(step) {
        current = step;
        panes.forEach(function (pane) {
            pane.classList.toggle('active', Number(pane.dataset.pane) === step);
        });
        tabs.forEach(function (tab) {
            var n = Number(tab.dataset.step);
            tab.classList.toggle('active', n === step);
            tab.classList.toggle('done', n < step);
        });
        backBtn.disabled = step === 1;
        nextBtn.hidden = step === TOTAL;
        submitBtn.hidden = step !== TOTAL;
        feedback.textContent = '';
        if (step === 2) initMap();
        if (step === 4) renderReview();
    }

    function fail(message) {
        feedback.textContent = message;
        App.toast(message, 'error');
    }

    function valid(step) {
        if (step === 1) {
            var title = document.getElementById('wzTitle').value.trim();
            var description = document.getElementById('wzDescription').value.trim();
            var category = document.getElementById('wzCategory').value;
            if (!title) { fail('A title is required.'); return false; }
            if (!description) { fail('Please describe the issue.'); return false; }
            if (!category) { fail('Please select a category.'); return false; }
            return true;
        }
        if (step === 2) {
            if (!document.getElementById('wzLatitude').value) {
                fail('Drop a pin on the map to set the location.');
                return false;
            }
            return true;
        }
        if (step === 3) {
            if (photos.length < 1) { fail('Attach at least one photo.'); return false; }
            if (photos.length > 5) { fail('You can upload up to 5 photos.'); return false; }
            return true;
        }
        return true;
    }

    backBtn.addEventListener('click', function () {
        if (current > 1) show(current - 1);
    });
    nextBtn.addEventListener('click', function () {
        if (valid(current)) show(current + 1);
    });

    /* Step 2 — Leaflet pin + dynamic ward detection. */
    function initMap() {
        if (map) {
            setTimeout(function () { map.invalidateSize(); }, 50);
            return;
        }
        map = L.map('wizardMap').setView([23.8103, 90.4125], 12);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);
        map.on('click', function (e) {
            setPin(e.latlng.lat, e.latlng.lng);
        });
        setTimeout(function () { map.invalidateSize(); }, 50);
    }

    function setPin(lat, lng) {
        document.getElementById('wzLatitude').value = lat.toFixed(6);
        document.getElementById('wzLongitude').value = lng.toFixed(6);
        if (marker) {
            marker.setLatLng([lat, lng]);
        } else {
            marker = L.marker([lat, lng], { draggable: true }).addTo(map);
            marker.on('dragend', function (e) {
                var pos = e.target.getLatLng();
                setPin(pos.lat, pos.lng);
            });
        }
        lookupWard(lat, lng);
    }

    document.getElementById('wzLocate').addEventListener('click', function () {
        if (!navigator.geolocation) {
            fail('Location services are not supported by this browser.');
            return;
        }
        navigator.geolocation.getCurrentPosition(function (pos) {
            initMap();
            map.setView([pos.coords.latitude, pos.coords.longitude], 15);
            setPin(pos.coords.latitude, pos.coords.longitude);
        }, function () {
            fail('We could not get your location. Click the map instead.');
        }, { enableHighAccuracy: true, timeout: 10000 });
    });

    function lookupWard(lat, lng) {
        var info = document.getElementById('wzWardInfo');
        clearTimeout(wardTimer);
        wardTimer = setTimeout(function () {
            fetch('/api/public/wards/lookup?lat=' + lat + '&lng=' + lng)
                .then(function (res) { return res.ok ? res.json() : null; })
                .then(function (data) {
                    info.textContent = data && data.areaName
                        ? 'Detected ward: ' + data.areaName + (data.wardNumber ? ' (Ward ' + data.wardNumber + ')' : '')
                        : 'No ward found for this location — you can still submit.';
                })
                .catch(function () {
                    info.textContent = 'Ward lookup unavailable — you can still submit.';
                });
        }, 300);
    }

    /* Step 3 — drag-and-drop photos with previews. */
    var dropZone = document.getElementById('wzDropZone');
    var fileInput = document.getElementById('wzPhotos');
    var photoList = document.getElementById('wzPhotoList');

    dropZone.addEventListener('click', function () { fileInput.click(); });
    ['dragover', 'dragenter'].forEach(function (evt) {
        dropZone.addEventListener(evt, function (e) {
            e.preventDefault();
            dropZone.classList.add('dragover');
        });
    });
    ['dragleave', 'drop'].forEach(function (evt) {
        dropZone.addEventListener(evt, function (e) {
            e.preventDefault();
            dropZone.classList.remove('dragover');
        });
    });
    dropZone.addEventListener('drop', function (e) {
        addFiles(e.dataTransfer.files);
    });
    fileInput.addEventListener('change', function () {
        addFiles(fileInput.files);
        fileInput.value = '';
    });

    function addFiles(files) {
        Array.from(files || []).forEach(function (file) {
            if (photos.length >= 5) {
                fail('You can upload up to 5 photos.');
                return;
            }
            photos.push(file);
        });
        renderPhotos();
    }

    function renderPhotos() {
        photoList.innerHTML = '';
        photos.forEach(function (file, index) {
            var item = document.createElement('li');
            var url = URL.createObjectURL(file);
            item.innerHTML = '<img alt="photo preview">'
                + '<button type="button" aria-label="Remove photo">&times;</button>';
            item.querySelector('img').src = url;
            item.querySelector('button').addEventListener('click', function () {
                URL.revokeObjectURL(url);
                photos.splice(index, 1);
                renderPhotos();
            });
            photoList.appendChild(item);
        });
    }

    /* Step 4 — review. */
    function renderReview() {
        var lat = document.getElementById('wzLatitude').value;
        var lng = document.getElementById('wzLongitude').value;
        document.getElementById('wzReview').innerHTML =
            '<div class="review-row"><dt>Title</dt><dd>' + App.esc(document.getElementById('wzTitle').value) + '</dd></div>'
            + '<div class="review-row"><dt>Description</dt><dd>' + App.esc(document.getElementById('wzDescription').value) + '</dd></div>'
            + '<div class="review-row"><dt>Category</dt><dd>' + App.esc(document.getElementById('wzCategory').value) + '</dd></div>'
            + '<div class="review-row"><dt>Location</dt><dd>' + App.esc(lat) + ', ' + App.esc(lng) + '</dd></div>'
            + '<div class="review-row"><dt>Photos</dt><dd>' + photos.length + ' attached</dd></div>';
    }

    form.addEventListener('submit', async function (event) {
        event.preventDefault();
        if (!valid(1) || !valid(2) || !valid(3)) {
            show(1);
            return;
        }
        submitBtn.disabled = true;
        feedback.textContent = '';
        try {
            var body = new FormData();
            body.append('title', document.getElementById('wzTitle').value.trim());
            body.append('description', document.getElementById('wzDescription').value.trim());
            body.append('category', document.getElementById('wzCategory').value);
            body.append('latitude', document.getElementById('wzLatitude').value);
            body.append('longitude', document.getElementById('wzLongitude').value);
            photos.forEach(function (file) { body.append('photos', file); });
            var result = await App.apiJson('/api/complaints', { method: 'POST', body: body });
            App.toast('Report ' + result.referenceCode + ' submitted.', 'success');
            setTimeout(function () {
                window.location.assign('/citizen/dashboard');
            }, 600);
        } catch (error) {
            fail(error.message);
        } finally {
            submitBtn.disabled = false;
        }
    });

    show(1);
})();
