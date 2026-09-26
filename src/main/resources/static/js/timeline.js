/* Complaint timeline (U11): initial render + 30s polling of the complaint endpoint.
 * Reference resolves from /citizen/complaints/{ref} path first, then ?ref= fallback. */
function detailRef() {
    var segments = window.location.pathname.split('/').filter(Boolean);
    if (segments.length >= 3 && segments[0] === 'citizen' && segments[1] === 'complaints') {
        return decodeURIComponent(segments[2]);
    }
    return new URLSearchParams(window.location.search).get('ref');
}

function statusBadgeHtml(status) {
    var cls = 'badge-' + (status || 'SUBMITTED');
    return '<span class="badge-status ' + cls + '">' + (status || 'SUBMITTED') + '</span>';
}

async function loadDetail() {
    var ref = detailRef();
    if (!ref) return;
    var res = await fetch('/api/complaints/' + ref, {
        headers: { 'Authorization': 'Bearer ' + localStorage.getItem('nagorikSebaToken') }
    });
    if (res.status === 401) {
        window.location.replace('/login?next=' + encodeURIComponent(window.location.pathname));
        return;
    }
    if (!res.ok) return;
    var complaint = await res.json();

    var titleEl = document.getElementById('title');
    if (titleEl) titleEl.textContent = complaint.title || 'Untitled Grievance';

    var refEl = document.getElementById('refDisplay');
    if (refEl) refEl.textContent = '# ' + (complaint.referenceCode || ref);

    var badgeEl = document.getElementById('statusBadgeDisplay');
    if (badgeEl) badgeEl.innerHTML = statusBadgeHtml(complaint.status);

    var metaEl = document.getElementById('meta');
    if (metaEl) {
        var wardInfo = complaint.wardNumber ? ('Ward ' + complaint.wardNumber + (complaint.areaName ? ' (' + complaint.areaName + ')' : '')) : 'Zone Unassigned';
        var catInfo = complaint.category || 'GENERAL';
        var dateInfo = complaint.submittedAt ? new Date(complaint.submittedAt).toLocaleString() : '';
        metaEl.textContent = catInfo + ' · ' + wardInfo + (dateInfo ? ' · ' + dateInfo : '');
    }

    var timelineEl = document.getElementById('timeline');
    if (timelineEl) {
        var steps = complaint.timeline || [];
        if (steps.length === 0) {
            timelineEl.innerHTML = '<li class="text-xs font-mono text-secondary py-2">No timeline updates recorded yet.</li>';
        } else {
            timelineEl.innerHTML = steps.map(function (step, idx) {
                var stepDate = step.createdAt ? new Date(step.createdAt).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' }) : '';
                return '<li class="relative pl-6 pb-4 border-l border-outline-variant last:pb-0">'
                    + '<div class="absolute -left-1.5 top-0 w-3 h-3 bg-primary border-2 border-white"></div>'
                    + '<div class="flex items-center justify-between gap-2">'
                    + '<div>' + statusBadgeHtml(step.toStatus) + '</div>'
                    + '<span class="text-[10px] font-mono text-secondary">' + stepDate + '</span>'
                    + '</div>'
                    + '<div class="text-[11px] font-mono text-secondary mt-1">Logged by: <strong class="text-on-surface">' + (step.actorName || 'System Service') + '</strong></div>'
                    + (step.note ? '<div class="mt-1.5 p-2 bg-surface-container-low border border-outline-variant text-xs font-sans text-on-surface">' + step.note + '</div>' : '')
                    + '</li>';
            }).join('');
        }
    }

    var galleryEl = document.getElementById('gallery');
    if (galleryEl) {
        var photos = complaint.attachments || [];
        if (photos.length === 0) {
            galleryEl.innerHTML = '<div class="col-span-full py-4 text-center text-xs font-mono text-secondary">No photographic verification on file.</div>';
        } else {
            galleryEl.innerHTML = photos.map(function (photo) {
                return '<div class="relative group cursor-pointer border border-outline-variant overflow-hidden bg-surface-container-low">'
                    + '<img src="/uploads/' + photo.storageKey + '" class="w-full h-32 object-cover transition-transform group-hover:scale-105" alt="Evidence photo">'
                    + (photo.workProof ? '<span class="absolute top-1 right-1 bg-emerald-700 text-white text-[9px] font-mono uppercase px-1 py-0.5">Work Proof</span>' : '')
                    + '</div>';
            }).join('');
        }
    }
}

setInterval(function () {
    if (!detailRef() || !document.getElementById('timeline')) return;
    loadDetail();
}, 30000);
