/* Complaint timeline (U11): initial render + 30s polling of the complaint endpoint.
 * Reference resolves from /citizen/complaints/{ref} path first, then ?ref= fallback. */
function detailRef() {
    var segments = window.location.pathname.split('/').filter(Boolean);
    if (segments.length >= 3 && segments[0] === 'citizen' && segments[1] === 'complaints') {
        return decodeURIComponent(segments[2]);
    }
    return new URLSearchParams(window.location.search).get('ref');
}
async function loadDetail() {
    var ref = detailRef();
    if (!ref) return;
    var res = await fetch('/api/complaints/' + ref, {
        headers: {'Authorization': 'Bearer ' + localStorage.getItem('nagorikSebaToken')}
    });
    if (res.status === 401) {
        window.location.replace('/login?next=' + encodeURIComponent(window.location.pathname));
        return;
    }
    if (!res.ok) return;
    var complaint = await res.json();
    document.getElementById('title').textContent = complaint.title || 'Complaint';
    document.getElementById('meta').textContent =
        (complaint.referenceCode || '') + ' — ' + (complaint.status || '');
    document.getElementById('timeline').innerHTML = (complaint.timeline || []).map(function (step) {
        return '<li class="list-group-item"><strong>' + step.toStatus + '</strong>'
            + ' <small class="text-muted">' + (step.actorName || 'System') + ' — '
            + (step.createdAt || '') + '</small>'
            + (step.note ? '<div>' + step.note + '</div>' : '') + '</li>';
    }).join('');
    document.getElementById('gallery').innerHTML = (complaint.attachments || []).map(function (photo) {
        return '<div class="col-md-4 mb-2"><img src="/uploads/' + photo.storageKey
            + '" class="img-fluid rounded" alt="complaint photo"></div>';
    }).join('');
}

setInterval(function () {
    if (!detailRef() || !document.getElementById('timeline')) return;
    loadDetail();
}, 30000);
