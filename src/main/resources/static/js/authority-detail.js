/* Authority complaint detail: audit log, evidence viewer, map, advancement panel. */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login/authority?next=' + encodeURIComponent(window.location.pathname));
        return;
    }

    var ACTION_BUTTONS = {
        SUBMITTED: [['verify', 'Verify', 'btn-success'], ['reject', 'Reject', 'btn-danger']],
        VERIFIED: [['assign', 'Assign (auto)', 'btn-primary']],
        REOPENED: [['assign', 'Assign (auto)', 'btn-primary']],
        ASSIGNED: [['start', 'Start Work', 'btn-primary']],
        IN_PROGRESS: [['resolve', 'Resolve (with proof photo)', 'btn-success']]
    };

    function ref() {
        var segments = window.location.pathname.split('/').filter(Boolean);
        return decodeURIComponent(segments[segments.length - 1]);
    }

    function renderAudit(complaint) {
        var rows = [
            ['Reference', complaint.referenceCode],
            ['Title', complaint.title],
            ['Category', complaint.category],
            ['Priority', complaint.priority],
            ['Ward', complaint.wardName || '—'],
            ['Citizen', complaint.citizenName || 'Anonymous'],
            ['Contact', complaint.citizenPhone || '—'],
            ['Submitted', complaint.submittedAt || '—'],
            ['Reopens', complaint.reopenCount]
        ];
        document.getElementById('auditTable').querySelector('tbody').innerHTML = rows.map(function (row) {
            return '<tr><th class="text-muted">' + row[0] + '</th><td>' + App.esc(row[1]) + '</td></tr>';
        }).join('');
    }

    function renderMap(complaint) {
        if (!window.L || complaint.latitude == null) return;
        var map = L.map('authorityMap').setView([complaint.latitude, complaint.longitude], 15);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);
        L.marker([complaint.latitude, complaint.longitude]).addTo(map);
        setTimeout(function () { map.invalidateSize(); }, 50);
    }

    function renderGallery(complaint) {
        document.getElementById('authorityGallery').innerHTML =
            (complaint.attachments || []).map(function (photo) {
                return '<div class="col-md-4 mb-2"><img src="/uploads/' + photo.storageKey
                    + '" class="img-fluid rounded" alt="evidence photo" data-full="/uploads/'
                    + photo.storageKey + '"></div>';
            }).join('') || '<p class="text-muted">No photos attached.</p>';
    }

    function renderTimeline(complaint) {
        document.getElementById('authorityTimeline').innerHTML =
            (complaint.timeline || []).map(function (step) {
                return '<li><strong>' + App.esc(step.toStatus) + '</strong> '
                    + '<span class="text-muted">' + App.esc(step.action || '') + ' — '
                    + App.esc(step.actorName || 'System') + ' (' + App.esc(step.actorRole || '') + ')</span>'
                    + '<div class="text-muted"><small>' + App.esc(step.createdAt || '') + '</small></div>'
                    + (step.note ? '<div>' + App.esc(step.note) + '</div>' : '') + '</li>';
            }).join('');
    }

    function renderActions(complaint) {
        var buttons = ACTION_BUTTONS[complaint.status] || [];
        var box = document.getElementById('actionButtons');
        if (buttons.length === 0) {
            box.innerHTML = '<p class="text-muted mb-0">No actions available in status '
                + App.esc(complaint.status) + '.</p>';
            return;
        }
        box.innerHTML = buttons.map(function (button) {
            return '<button class="btn ' + button[2] + '" data-action="' + button[0] + '">'
                + button[1] + '</button>';
        }).join('');
        box.querySelectorAll('button').forEach(function (button) {
            button.addEventListener('click', function () { runAction(button.dataset.action); });
        });
    }

    async function runAction(action) {
        var note = document.getElementById('advNote').value.trim();
        var path = '/api/authority/complaints/' + ref() + '/' + action;
        try {
            var options = { method: 'POST' };
            if (action === 'reject' && !note) {
                App.toast('A reason is required to reject.', 'error');
                return;
            }
            if (action === 'verify' || action === 'reject' || action === 'start') {
                path += (action === 'reject' ? '?reason=' : '?note=') + encodeURIComponent(note);
            } else if (action === 'assign') {
                path += '/auto' + (note ? '?note=' + encodeURIComponent(note) : '');
            } else if (action === 'resolve') {
                App.toast('Resolving needs a proof photo — use the queue Resolve dialog.', 'error');
                return;
            }
            await App.apiJson(path, options);
            App.toast('Complaint ' + action + 'd.', 'success');
            load();
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }

    function openLightbox(src) {
        document.getElementById('authorityLightboxImg').src = src;
        document.getElementById('authorityLightbox').classList.remove('hidden');
    }

    function closeLightbox() {
        document.getElementById('authorityLightbox').classList.add('hidden');
        document.getElementById('authorityLightboxImg').removeAttribute('src');
    }

    async function load() {
        try {
            var complaint = await App.apiJson('/api/complaints/' + ref());
            document.getElementById('detailTitle').textContent = complaint.title || 'Complaint';
            document.getElementById('detailMeta').textContent =
                complaint.referenceCode + ' — ' + complaint.status;
            renderAudit(complaint);
            renderMap(complaint);
            renderGallery(complaint);
            renderTimeline(complaint);
            renderActions(complaint);
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }

    document.getElementById('authorityGallery').addEventListener('click', function (event) {
        var img = event.target.closest('img');
        if (img) openLightbox(img.dataset.full || img.src);
    });
    document.getElementById('authorityLightboxClose').addEventListener('click', closeLightbox);
    document.getElementById('authorityLightbox').addEventListener('click', function (event) {
        if (event.target.id === 'authorityLightbox') closeLightbox();
    });
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') closeLightbox();
    });
    document.addEventListener('DOMContentLoaded', load);
})();
