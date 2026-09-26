/* ==========================================================================
   Authority Complaint Detail Engine: Stitch Modernized Case Dossier
   ========================================================================== */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login/authority?next=' + encodeURIComponent(window.location.pathname));
        return;
    }

    var ACTION_BUTTONS = {
        SUBMITTED: [
            ['verify', 'Verify Dossier', 'bg-emerald-700 hover:bg-emerald-800 text-white'],
            ['reject', 'Reject Grievance', 'bg-surface-container-lowest hover:bg-red-50 text-red-700 border border-red-600']
        ],
        VERIFIED: [
            ['assign', 'Auto-Assign Department', 'bg-primary-container hover:bg-blue-700 text-white']
        ],
        REOPENED: [
            ['assign', 'Re-Assign Department', 'bg-primary-container hover:bg-blue-700 text-white']
        ],
        ASSIGNED: [
            ['start', 'Start Field Work', 'bg-inverse-surface hover:bg-black text-white']
        ],
        IN_PROGRESS: [
            ['resolve', 'Complete & Upload Proof', 'bg-emerald-700 hover:bg-emerald-800 text-white']
        ]
    };

    function ref() {
        var segments = window.location.pathname.split('/').filter(Boolean);
        return decodeURIComponent(segments[segments.length - 1]);
    }

    function statusBadgeHtml(status) {
        var cls = 'badge-' + (status || 'SUBMITTED');
        return '<span class="badge-status ' + cls + '">' + (status || 'SUBMITTED') + '</span>';
    }

    function priorityBadgeHtml(priority) {
        var cls = 'badge-priority-' + (priority || 'NORMAL');
        return '<span class="badge-status ' + cls + '">Priority: ' + (priority || 'NORMAL') + '</span>';
    }

    function renderAudit(complaint) {
        var rows = [
            ['Statutory Reference', complaint.referenceCode],
            ['Grievance Title', complaint.title],
            ['Category', complaint.category],
            ['Assigned Priority', complaint.priority],
            ['Ward Jurisdiction', complaint.wardName || 'Zone Unassigned'],
            ['Complainant Identity', complaint.citizenName || 'Confidential / Citizen'],
            ['Direct Phone', complaint.citizenPhone || 'Encrypted on record'],
            ['Submission Timestamp', complaint.submittedAt ? new Date(complaint.submittedAt).toLocaleString() : '—'],
            ['Reopen Count', String(complaint.reopenCount || 0) + ' Cycles']
        ];

        var auditBody = document.querySelector('#auditTable tbody');
        if (auditBody) {
            auditBody.innerHTML = rows.map(function (row) {
                return '<tr><th class="py-2 pr-4 font-semibold text-secondary w-1/3 text-left">' + row[0] + '</th>'
                    + '<td class="py-2 text-on-surface">' + App.esc(row[1]) + '</td></tr>';
            }).join('');
        }

        // Fill metric strip
        var tileWard = document.getElementById('tileWard');
        if (tileWard) tileWard.textContent = complaint.wardName || 'Ward ' + (complaint.wardNumber || '—');

        var tileDept = document.getElementById('tileDept');
        if (tileDept) tileDept.textContent = complaint.category || 'Central Dispatch';

        var tileOfficer = document.getElementById('tileOfficer');
        if (tileOfficer) tileOfficer.textContent = complaint.assignedOfficerName || 'Pending Allocation';

        var tileReopens = document.getElementById('tileReopens');
        if (tileReopens) tileReopens.textContent = (complaint.reopenCount || 0) + ' Cycles';

        var titleRef = document.getElementById('detailTitleRef');
        if (titleRef) titleRef.textContent = '# ' + complaint.referenceCode;

        var metaRef = document.getElementById('detailMetaRef');
        if (metaRef) metaRef.textContent = complaint.referenceCode;

        var badges = document.getElementById('detailBadges');
        if (badges) {
            badges.innerHTML = '<span class="font-mono text-xl md:text-2xl font-bold text-on-surface mr-2"># ' + complaint.referenceCode + '</span>'
                + statusBadgeHtml(complaint.status) + ' '
                + priorityBadgeHtml(complaint.priority);
        }
    }

    function renderMap(complaint) {
        var mapEl = document.getElementById('authorityMap');
        if (!mapEl || !window.L || complaint.latitude == null) return;
        mapEl.innerHTML = '';
        var map = L.map('authorityMap').setView([complaint.latitude, complaint.longitude], 15);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);
        L.marker([complaint.latitude, complaint.longitude]).addTo(map)
            .bindPopup('<strong>' + App.esc(complaint.referenceCode) + '</strong><br>' + App.esc(complaint.title)).openPopup();
        setTimeout(function () { map.invalidateSize(); }, 50);
    }

    function renderGallery(complaint) {
        var gallery = document.getElementById('authorityGallery');
        if (!gallery) return;
        var photos = complaint.attachments || [];
        if (photos.length === 0) {
            gallery.innerHTML = '<p class="text-xs text-secondary font-mono col-span-full py-4 text-center">No photographic verification on file.</p>';
            return;
        }
        gallery.innerHTML = photos.map(function (photo) {
            return '<div class="relative group cursor-pointer border border-outline-variant overflow-hidden bg-surface-container-low">'
                + '<img src="/uploads/' + photo.storageKey + '" class="w-full h-28 object-cover transition-transform group-hover:scale-105" alt="Evidence photo" data-full="/uploads/' + photo.storageKey + '">'
                + (photo.workProof ? '<span class="absolute top-1 right-1 bg-emerald-700 text-white text-[9px] font-mono uppercase px-1 py-0.5">Work Proof</span>' : '')
                + '</div>';
        }).join('');
    }

    var STAGES = ['SUBMITTED', 'VERIFIED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED'];

    function renderStages(complaint) {
        var tracker = document.getElementById('stageTracker');
        if (!tracker) return;
        var reached = STAGES.indexOf(complaint.status);
        if (reached < 0) {
            tracker.innerHTML = '<li class="p-2 bg-red-50 text-red-800 border border-red-300 font-bold uppercase text-xs">Terminal Status: ' + App.esc(complaint.status) + '</li>';
            return;
        }
        tracker.innerHTML = STAGES.map(function (stage, index) {
            var isDone = index < reached;
            var isCurrent = index === reached;
            var cls = isDone ? 'bg-surface-container-low text-emerald-800 border border-emerald-300 font-semibold'
                : (isCurrent ? 'bg-inverse-surface text-white border border-inverse-surface font-bold' : 'bg-surface-container-lowest text-outline border border-outline-variant');
            var icon = isDone ? 'check' : (isCurrent ? 'play_arrow' : 'schedule');
            return '<li class="p-2 flex items-center justify-between ' + cls + '">'
                + '<div class="flex items-center gap-2">'
                + '<span class="material-symbols-outlined text-sm">' + icon + '</span>'
                + '<span>' + stage.replace(/_/g, ' ') + '</span>'
                + '</div>'
                + '<span class="text-[10px]">' + (index + 1) + '/5</span>'
                + '</li>';
        }).join('');
    }

    function renderTimeline(complaint) {
        var timeline = document.getElementById('authorityTimeline');
        if (!timeline) return;
        var steps = complaint.timeline || [];
        if (steps.length === 0) {
            timeline.innerHTML = '<li class="text-xs text-secondary font-mono py-2">No timeline updates recorded.</li>';
            return;
        }
        timeline.innerHTML = steps.map(function (step) {
            var stepDate = step.createdAt ? new Date(step.createdAt).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' }) : '';
            return '<li class="relative pl-5 pb-3 border-l border-outline-variant last:pb-0">'
                + '<div class="absolute -left-1.5 top-0 w-3 h-3 bg-primary border-2 border-white"></div>'
                + '<div class="flex items-center justify-between gap-2">'
                + '<div>' + statusBadgeHtml(step.toStatus) + '</div>'
                + '<span class="text-[10px] text-secondary">' + stepDate + '</span>'
                + '</div>'
                + '<div class="text-[11px] text-secondary mt-1">Logged by: <strong class="text-on-surface">' + (step.actorName || 'System') + ' (' + (step.actorRole || 'SYSTEM') + ')</strong></div>'
                + (step.note ? '<div class="mt-1 p-1.5 bg-surface-container-low border border-outline-variant text-xs text-on-surface font-sans">' + App.esc(step.note) + '</div>' : '')
                + '</li>';
        }).join('');
    }

    function renderActions(complaint) {
        var buttons = ACTION_BUTTONS[complaint.status] || [];
        var box = document.getElementById('actionButtons');
        if (!box) return;
        if (buttons.length === 0) {
            box.innerHTML = '<p class="text-secondary text-xs font-mono mb-0">No active transitions in status ' + App.esc(complaint.status) + '.</p>';
            return;
        }
        box.innerHTML = buttons.map(function (button) {
            return '<button class="w-full font-mono text-xs uppercase font-bold py-2.5 px-4 flex items-center justify-center gap-1.5 transition-colors ' + button[2] + '" data-action="' + button[0] + '">'
                + '<span>' + button[1] + '</span>'
                + '<span class="material-symbols-outlined text-sm">arrow_forward</span>'
                + '</button>';
        }).join('');
        box.querySelectorAll('button').forEach(function (button) {
            button.addEventListener('click', function () { runAction(button.dataset.action); });
        });
    }

    function idempotencyKey() {
        try {
            return (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now());
        } catch (e) {
            return String(Date.now());
        }
    }

    function openResolveModal() {
        var modal = document.getElementById('detailResolveModal');
        if (!modal) return;
        document.getElementById('detailResolveRef').textContent = ref();
        document.getElementById('detailResolveNote').value = document.getElementById('advNote').value.trim();
        document.getElementById('detailResolveFeedback').textContent = '';
        document.getElementById('detailResolvePhotos').value = '';
        modal.classList.remove('hidden');
    }

    function closeResolveModal() {
        var modal = document.getElementById('detailResolveModal');
        if (modal) modal.classList.add('hidden');
    }

    async function submitResolveModal() {
        var photos = document.getElementById('detailResolvePhotos').files;
        if (!photos || photos.length < 1) {
            document.getElementById('detailResolveFeedback').textContent = 'A work-proof photo is required by statute.';
            return;
        }
        var body = new FormData();
        body.append('note', document.getElementById('detailResolveNote').value);
        body.append('photos', photos[0]);
        try {
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(ref()) + '/resolve',
                { method: 'POST', body: body, headers: { 'Idempotency-Key': idempotencyKey() } });
            App.toast('Resolution committed and audited.', 'success');
            closeResolveModal();
            load();
        } catch (error) {
            document.getElementById('detailResolveFeedback').textContent = error.message;
        }
    }

    async function runAction(action) {
        var note = document.getElementById('advNote').value.trim();
        var path = '/api/authority/complaints/' + encodeURIComponent(ref()) + '/' + action;
        try {
            var options = { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey() } };
            if (action === 'reject' && !note) {
                App.toast('A reason is required to reject.', 'error');
                return;
            }
            if (action === 'verify' || action === 'reject' || action === 'start') {
                path += (action === 'reject' ? '?reason=' : '?note=') + encodeURIComponent(note);
            } else if (action === 'assign') {
                path += '/auto' + (note ? '?note=' + encodeURIComponent(note) : '');
            } else if (action === 'resolve') {
                openResolveModal();
                return;
            }
            await App.apiJson(path, options);
            App.toast('Transition committed successfully: ' + action.toUpperCase(), 'success');
            document.getElementById('advNote').value = '';
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
            var complaint = await App.apiJson('/api/complaints/' + encodeURIComponent(ref()));
            document.getElementById('detailTitle').textContent = complaint.title || 'Untitled Grievance';
            document.getElementById('detailMeta').textContent =
                complaint.referenceCode + ' · Ward ' + (complaint.wardNumber || '—') + ' · ' + (complaint.category || 'GENERAL');
            renderAudit(complaint);
            renderMap(complaint);
            renderGallery(complaint);
            renderStages(complaint);
            renderTimeline(complaint);
            renderActions(complaint);
        } catch (error) {
            App.toast('Failed to load dossier: ' + error.message, 'error');
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        var resolveCancel = document.getElementById('detailResolveCancel');
        if (resolveCancel) resolveCancel.addEventListener('click', closeResolveModal);

        var resolveConfirm = document.getElementById('detailResolveConfirm');
        if (resolveConfirm) resolveConfirm.addEventListener('click', submitResolveModal);

        var lightboxClose = document.getElementById('authorityLightboxClose');
        if (lightboxClose) lightboxClose.addEventListener('click', closeLightbox);

        var lightbox = document.getElementById('authorityLightbox');
        if (lightbox) {
            lightbox.addEventListener('click', function (event) {
                if (event.target.id === 'authorityLightbox') closeLightbox();
            });
        }

        var gallery = document.getElementById('authorityGallery');
        if (gallery) {
            gallery.addEventListener('click', function (event) {
                var img = event.target.closest('img');
                if (img) openLightbox(img.dataset.full || img.src);
            });
        }

        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') closeLightbox();
        });

        load();
    });
})();
