/* ==========================================================================
   Admin Overview Cards Engine (Stitch Modernized KPI Tiles)
   ========================================================================== */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/admin');
        return;
    }

    function card(title, value, metricId, topBorderColor, iconName) {
        return '<div class="bg-surface-container-lowest border border-outline-variant border-t-2 ' + topBorderColor + ' p-4">'
            + '<div class="flex justify-between items-center mb-2">'
            + '<span class="text-[10px] font-mono uppercase text-secondary font-bold tracking-wider">' + title + '</span>'
            + '<span class="text-[9px] font-mono text-outline">' + metricId + '</span>'
            + '</div>'
            + '<div class="flex items-baseline justify-between">'
            + '<div class="font-mono text-3xl font-bold text-on-surface">' + value + '</div>'
            + '<span class="material-symbols-outlined text-outline text-2xl">' + iconName + '</span>'
            + '</div>'
            + '<p class="text-[11px] text-secondary mt-1 font-mono">Registered on ledger</p>'
            + '</div>';
    }

    async function load() {
        var container = document.getElementById('overviewCards');
        if (!container) return;
        try {
            var results = await Promise.all([
                App.apiJson('/api/admin/municipalities'),
                App.apiJson('/api/admin/departments'),
                App.apiJson('/api/admin/users'),
                App.apiJson('/api/admin/sla-policies')
            ]);
            container.innerHTML =
                card('City Corporations', results[0].length, 'CORP-01', 'border-t-primary-container', 'location_city')
                + card('Zonal Departments', results[1].length, 'DEPT-02', 'border-t-teal-700', 'corporate_fare')
                + card('Authorized Users', results[2].length, 'USER-03', 'border-t-amber-700', 'group')
                + card('SLA Policy Matrix', results[3].length, 'RULE-04', 'border-t-purple-700', 'gavel');
        } catch (error) {
            container.innerHTML = '<div class="col-span-full py-4 text-center font-mono text-xs text-error">Error loading overview metrics: ' + App.esc(error.message) + '</div>';
            App.toast(error.message, 'error');
        }
    }

    document.addEventListener('DOMContentLoaded', load);
})();
