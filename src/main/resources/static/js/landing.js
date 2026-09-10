/* Landing page: live Dhaka complaint map, ward table, menu, toasts. */
(function () {
    'use strict';

    function toast(message, type) {
        var box = document.getElementById('landing-toasts');
        if (!box) return;
        var el = document.createElement('div');
        el.className = 'landing-toast' + (type ? ' ' + type : '');
        el.textContent = message;
        box.appendChild(el);
        setTimeout(function () {
            if (el.parentNode) el.parentNode.removeChild(el);
        }, 4000);
    }

    var hamburger = document.getElementById('landingHamburger');
    var mobileMenu = document.getElementById('landingMobileMenu');
    if (hamburger && mobileMenu) {
        hamburger.addEventListener('click', function () {
            mobileMenu.classList.toggle('open');
        });
    }

    function dot(className) {
        return L.divIcon({
            className: '',
            html: '<span class="landing-dot ' + className + '"></span>',
            iconSize: [20, 20],
            iconAnchor: [10, 10],
            popupAnchor: [0, -10]
        });
    }

    function statusClass(status) {
        if (status === 'RESOLVED' || status === 'CLOSED') return 'landing-dot-resolved';
        return 'landing-dot-active';
    }

    function loadMarkers(map) {
        var url = '/api/public/heatmap?municipality=dhaka-north'
            + '&minLng=90.36&minLat=23.72&maxLng=90.44&maxLat=23.88';
        fetch(url).then(function (res) {
            if (!res.ok) throw new Error('heatmap ' + res.status);
            return res.json();
        }).then(function (data) {
            (data.points || []).forEach(function (point) {
                L.marker([point.lat, point.lng], { icon: dot(statusClass(point.status)) })
                    .addTo(map)
                    .bindPopup('<strong>' + point.referenceCode + '</strong>'
                        + '<span class="landing-popup-meta">' + point.category
                        + ' — ' + point.status + '</span>');
            });
        }).catch(function () {
            toast('Live map data is unavailable right now.', 'error');
        });
    }

    function loadWards() {
        var body = document.getElementById('landingWardBody');
        if (!body) return;
        fetch('/api/public/wards/scoreboard?municipality=dhaka-north').then(function (res) {
            if (!res.ok) throw new Error('scoreboard ' + res.status);
            return res.json();
        }).then(function (rows) {
            if (!rows || rows.length === 0) return;
            body.innerHTML = rows.map(function (row) {
                var pending = row.totalComplaints - row.resolvedComplaints;
                var days = row.averageResolutionHours == null
                    ? '—' : (row.averageResolutionHours / 24).toFixed(1) + ' days';
                return '<tr><td>Ward ' + row.wardNumber + ' - ' + row.areaName + '</td>'
                    + '<td><span class="landing-num-ok">' + row.resolvedComplaints + '</span></td>'
                    + '<td><span class="landing-num-bad">' + pending + '</span></td>'
                    + '<td>—</td>'
                    + '<td>' + days + '</td></tr>';
            }).join('');
        }).catch(function () {
            toast('Ward performance data is unavailable right now.', 'error');
        });
    }

    function init() {
        var el = document.getElementById('landing-map');
        if (!el || !window.L) return;
        var map = L.map('landing-map').setView([23.8103, 90.4125], 11);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);
        loadMarkers(map);
        loadWards();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
