/* ==========================================================================
   Citizen Dashboard Engine: Stitch Modernized Cards, Live Filters & Search
   ========================================================================== */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/citizen/dashboard');
        return;
    }

    var ACTIVE = ['SUBMITTED', 'VERIFIED', 'ASSIGNED', 'IN_PROGRESS', 'REOPENED'];
    var currentFilter = 'ALL';
    var searchTerm = '';
    var allComplaints = [];

    function card(title, value, metricLabel, topBorderClass, iconName) {
        return '<div class="bg-surface-container-lowest border border-outline-variant border-t-2 ' + topBorderClass + '">'
            + '<div class="p-3 border-b border-outline-variant flex justify-between items-center bg-surface-container-low">'
            + '<span class="text-[11px] font-mono font-bold uppercase tracking-wider text-secondary">' + title + '</span>'
            + '<span class="text-[10px] font-mono text-outline">' + metricLabel + '</span>'
            + '</div>'
            + '<div class="p-4 flex items-baseline justify-between">'
            + '<div>'
            + '<div class="font-mono text-3xl font-bold text-on-surface">' + value + '</div>'
            + '<p class="text-xs text-secondary mt-1">Official Municipal Ledger</p>'
            + '</div>'
            + '<span class="material-symbols-outlined text-outline text-3xl">' + iconName + '</span>'
            + '</div></div>';
    }

    function statusBadge(status) {
        var labels = {
            SUBMITTED: 'Submitted',
            VERIFIED: 'Verified',
            ASSIGNED: 'Assigned',
            IN_PROGRESS: 'In Progress',
            RESOLVED: 'Resolved',
            CLOSED: 'Closed',
            REOPENED: 'Reopened',
            REJECTED: 'Rejected',
            CANCELLED: 'Cancelled'
        };
        var cls = 'badge-' + (status || 'SUBMITTED');
        return '<span class="badge-status ' + cls + '">' + (labels[status] || status) + '</span>';
    }

    function row(complaint) {
        var ref = App.esc(complaint.referenceCode || 'NS-PENDING');
        var title = App.esc(complaint.title || 'Untitled Grievance');
        var desc = App.esc(complaint.description || '');
        var category = App.esc(complaint.category || 'CIVIC');
        var date = App.esc(complaint.submittedAt ? new Date(complaint.submittedAt).toLocaleDateString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : 'Recently');
        var ward = complaint.wardNumber ? ('Ward ' + complaint.wardNumber + (complaint.areaName ? ' · ' + complaint.areaName : '')) : 'Auto-Detect Jurisdiction';

        return '<article class="bg-surface-container-lowest border border-outline-variant hover:border-outline transition-colors">'
            + '<div class="px-4 py-2.5 bg-surface-container-low border-b border-outline-variant flex flex-wrap justify-between items-center gap-2">'
            + '<div class="flex items-center gap-3">'
            + '<span class="font-mono text-sm font-bold text-on-surface"># ' + ref + '</span>'
            + '<span class="text-outline">|</span>'
            + '<span class="text-[11px] font-mono font-semibold uppercase text-secondary tracking-wider">' + category + '</span>'
            + '</div>'
            + '<div>' + statusBadge(complaint.status) + '</div>'
            + '</div>'
            + '<div class="p-4">'
            + '<h2 class="text-base font-bold text-on-surface mb-1.5">' + title + '</h2>'
            + (desc ? '<p class="text-xs text-secondary line-clamp-2 mb-4 leading-relaxed">' + desc + '</p>' : '')
            + '<div class="pt-3 border-t border-outline-variant flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs">'
            + '<div class="flex flex-wrap items-center gap-4 text-secondary font-mono text-[11px]">'
            + '<span class="flex items-center gap-1">'
            + '<span class="material-symbols-outlined text-sm">schedule</span>' + date + '</span>'
            + '<span class="flex items-center gap-1">'
            + '<span class="material-symbols-outlined text-sm">location_on</span>' + App.esc(ward) + '</span>'
            + '</div>'
            + '<div>'
            + '<a class="bg-surface-container-lowest hover:bg-surface-container border border-inverse-surface text-on-surface font-mono font-semibold text-xs px-3 py-1.5 inline-flex items-center gap-1 transition-colors" href="/citizen/complaints/' + encodeURIComponent(complaint.referenceCode) + '">'
            + '<span>Inspect Dossier</span> <span class="material-symbols-outlined text-xs">arrow_forward</span></a>'
            + '</div>'
            + '</div></div></article>';
    }

    function visibleComplaints() {
        var list = allComplaints;
        if (currentFilter === 'ACTIVE') {
            list = list.filter(function (c) { return ACTIVE.indexOf(c.status) !== -1; });
        } else if (currentFilter === 'RESOLVED') {
            list = list.filter(function (c) { return ACTIVE.indexOf(c.status) === -1; });
        }

        if (searchTerm) {
            var term = searchTerm.toLowerCase();
            list = list.filter(function (c) {
                return (c.referenceCode && c.referenceCode.toLowerCase().indexOf(term) !== -1)
                    || (c.title && c.title.toLowerCase().indexOf(term) !== -1)
                    || (c.category && c.category.toLowerCase().indexOf(term) !== -1)
                    || (c.description && c.description.toLowerCase().indexOf(term) !== -1);
            });
        }
        return list;
    }

    function renderList() {
        var complaints = visibleComplaints();
        var empty = complaints.length === 0;
        var emptyEl = document.getElementById('emptyState');
        var listEl = document.getElementById('complaintList');
        
        if (emptyEl) emptyEl.hidden = !empty;
        if (listEl) {
            listEl.innerHTML = complaints.map(row).join('');
        }

        document.querySelectorAll('#filterTabs button').forEach(function (button) {
            var on = button.dataset.filter === currentFilter;
            button.className = on
                ? 'px-3 py-1.5 text-xs font-mono uppercase font-semibold border bg-inverse-surface text-white border-inverse-surface'
                : 'px-3 py-1.5 text-xs font-mono uppercase font-semibold border bg-surface-container-lowest text-on-surface border-outline-variant hover:bg-surface-container';
        });
    }

    async function load() {
        try {
            allComplaints = await App.apiJson('/api/complaints/my');
            var active = allComplaints.filter(function (c) { return ACTIVE.indexOf(c.status) !== -1; }).length;
            var resolved = allComplaints.length - active;

            var cardsEl = document.getElementById('statCards');
            if (cardsEl) {
                cardsEl.innerHTML = card('Total Filed Dossiers', allComplaints.length, 'METRIC-01', 'border-t-tertiary', 'inventory_2')
                    + card('Active / In-Progress', active, 'ACTION REQ.', 'border-t-primary-container', 'pending_actions')
                    + card('Resolved & Ratified', resolved, 'RESOLVED', 'border-t-emerald-600', 'verified');
            }

            var sumEl = document.getElementById('dashboardSummary');
            if (sumEl) {
                sumEl.textContent = allComplaints.length === 0
                    ? 'No grievances submitted yet. File your first report below.'
                    : 'Showing ' + allComplaints.length + ' grievances logged under your citizen identity.';
            }

            renderList();
        } catch (err) {
            var sumEl = document.getElementById('dashboardSummary');
            if (sumEl) sumEl.textContent = 'Unable to load complaints telemetry: ' + err.message;
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('#filterTabs button').forEach(function (button) {
            button.addEventListener('click', function () {
                currentFilter = button.dataset.filter;
                renderList();
            });
        });

        var searchInput = document.getElementById('searchInput');
        if (searchInput) {
            searchInput.addEventListener('input', function (e) {
                searchTerm = e.target.value.trim();
                renderList();
            });
        }

        load();
    });
})();
