/* Public wards page: scoreboard table + mobile menu.
 * Externalised from public/wards.html (page CSP blocks inline scripts).
 * The Leaflet heatmap itself is driven by heatmap.js. */
(function () {
    var hamburger = document.getElementById('wardsHamburger');
    var menu = document.getElementById('wardsMobileMenu');
    if (hamburger && menu) {
        hamburger.addEventListener('click', function () { menu.classList.toggle('open'); });
    }
    function currentMunicipality() {
        var el = document.getElementById('heatmapMunicipality');
        return el && el.value ? el.value : 'dhaka-north';
    }
    async function loadScoreboard() {
        var body = document.getElementById('wardsScoreBody');
        var feedback = document.getElementById('wardsFeedback');
        var status = document.getElementById('heatmapStatus');
        try {
            var res = await fetch('/api/public/wards/scoreboard?municipality=' + encodeURIComponent(currentMunicipality()));
            if (!res.ok) throw new Error('Scoreboard unavailable (HTTP ' + res.status + ')');
            var rows = await res.json();
            if (!rows || rows.length === 0) {
                body.innerHTML = '<tr><td colspan="5">No ward data for this period yet.</td></tr>';
                return;
            }
            body.innerHTML = rows.map(function (row) {
                var pending = (row.totalComplaints || 0) - (row.resolvedComplaints || 0);
                var days = row.averageResolutionHours == null ? '—' : (row.averageResolutionHours / 24).toFixed(1) + ' days';
                var rate = row.resolutionRate == null ? '—' : Number(row.resolutionRate).toFixed(1) + '%';
                return '<tr><td>Ward ' + row.wardNumber + ' - ' + row.areaName + '</td>'
                    + '<td><span class="landing-num-ok">' + row.resolvedComplaints + '</span></td>'
                    + '<td><span class="landing-num-bad">' + pending + '</span></td>'
                    + '<td>' + rate + '</td>'
                    + '<td>' + days + '</td></tr>';
            }).join('');
            feedback.textContent = rows.length + ' wards shown.';
            if (status) status.textContent = 'Live map active.';
        } catch (e) {
            body.innerHTML = '<tr><td colspan="5">Ward performance data is unavailable right now.</td></tr>';
            if (feedback) feedback.textContent = e.message;
        }
    }
    document.getElementById('heatmapMunicipality').addEventListener('change', loadScoreboard);
    document.addEventListener('DOMContentLoaded', loadScoreboard);
})();
