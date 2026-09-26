/* Authority dashboard: ward aggregates + SLA counters.
 * Externalised from authority/dashboard.html (page CSP blocks inline scripts). */
async function loadDashboard() {
    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login/authority?next=/authority/dashboard');
        return;
    }
    var mid = document.getElementById('municipalityId').value;
    var url = '/api/authority/dashboard' + (mid ? '?municipalityId=' + encodeURIComponent(mid) : '');
    var data;
    try {
        data = await App.apiJson(url);
    } catch (error) {
        App.toast(error.message, 'error');
        return;
    }
    var avg = data.averageResolutionHours == null ? '—' : Number(data.averageResolutionHours).toFixed(1) + ' hrs';

    document.getElementById('statCards').innerHTML =
        '<div class="bg-surface-container-lowest border border-outline-variant border-t-2 border-t-primary-container p-4">'
        + '<div class="text-[10px] font-mono uppercase text-secondary mb-1">OFFICIAL ROLE</div>'
        + '<div class="font-mono text-2xl font-bold text-on-surface">' + (data.role || 'OFFICER') + '</div>'
        + '<p class="text-[11px] text-secondary mt-1">Authorized Departmental Unit</p></div>'
        + '<div class="bg-surface-container-lowest border border-outline-variant border-t-2 border-t-amber-600 p-4">'
        + '<div class="text-[10px] font-mono uppercase text-secondary mb-1">AVG SLA TURNAROUND</div>'
        + '<div class="font-mono text-2xl font-bold text-on-surface">' + avg + '</div>'
        + '<p class="text-[11px] text-secondary mt-1">Rolling municipal mean</p></div>'
        + '<div class="bg-surface-container-lowest border border-outline-variant border-t-2 border-t-red-600 p-4">'
        + '<div class="text-[10px] font-mono uppercase text-secondary mb-1">SLA BREACH RISK</div>'
        + '<div class="font-mono text-2xl font-bold text-red-600">' + (data.slaAtRiskCount || 0) + '</div>'
        + '<p class="text-[11px] text-secondary mt-1">Requires immediate dispatch</p></div>';

    var rows = [];
    var max = 1;
    (data.wardStats || []).forEach(function (w) {
        Object.entries(w.counts || {}).forEach(function ([status, count]) { if (count > max) max = count; });
    });
    (data.wardStats || []).forEach(function (w) {
        Object.entries(w.counts || {}).forEach(function ([status, count]) {
            var pct = Math.round((count / max) * 100);
            rows.push('<tr><td class="py-2.5 px-3 font-semibold">' + (w.wardName || ('Ward ' + w.wardId)) + '</td>'
                + '<td class="py-2.5 px-3"><span class="badge-status badge-' + status + '">' + status + '</span></td>'
                + '<td class="py-2.5 px-3 font-bold">' + count + '</td>'
                + '<td class="py-2.5 px-3" style="min-width:160px"><div class="h-2 bg-surface-container overflow-hidden"><div class="bg-primary h-full" style="width:' + pct + '%"></div></div></td></tr>');
        });
    });
    document.querySelector('#wardTable tbody').innerHTML = rows.join('') || '<tr><td colspan="4" class="py-4 text-center text-secondary">No ward grievance data recorded.</td></tr>';
}

document.getElementById('loadBtn').addEventListener('click', loadDashboard);
(function prefillMunicipality() {
    try {
        var user = JSON.parse(localStorage.getItem('nagorikSebaUser') || 'null');
        var mids = user && user.municipalityIds ? Array.from(user.municipalityIds) : [];
        if (mids.length === 1) {
            document.getElementById('municipalityId').value = mids[0];
            loadDashboard();
        }
    } catch (e) { /* leave manual entry */ }
})();
