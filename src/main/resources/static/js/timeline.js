/* Complaint timeline (U11): initial render + 30s polling of the complaint endpoint. */
async function loadDetail() {
    var ref = new URLSearchParams(window.location.search).get('ref');
    if (!ref) return;
    var res = await fetch('/api/complaints/' + ref, {
        headers: {'Authorization': 'Bearer ' + localStorage.getItem('nagorikSebaToken')}
    });
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
    var ref = new URLSearchParams(window.location.search).get('ref');
    if (!ref || !document.getElementById('timeline')) return;
    loadDetail();
}, 30000);
