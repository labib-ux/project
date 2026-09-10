/* Citizen dashboard: stat cards + vertical complaint list + empty state + FAB target. */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/citizen/dashboard');
        return;
    }

    var ACTIVE = ['SUBMITTED', 'VERIFIED', 'ASSIGNED', 'IN_PROGRESS', 'REOPENED'];

    function card(title, value) {
        return '<div class="col-md-4 mb-3"><div class="card"><div class="card-body">'
            + '<h6 class="text-muted text-uppercase">' + title + '</h6>'
            + '<p class="h3 mb-0">' + value + '</p></div></div></div>';
    }

    function row(complaint) {
        return '<div class="card mb-3"><div class="card-body d-flex justify-content-between align-items-center gap-3">'
            + '<div><div><code>' + App.esc(complaint.referenceCode) + '</code></div>'
            + '<div class="fw-bold">' + App.esc(complaint.title) + '</div>'
            + '<small class="text-muted">' + App.esc(complaint.category || '')
            + ' — ' + App.esc(complaint.submittedAt || '') + '</small></div>'
            + '<div class="d-flex align-items-center gap-2">'
            + App.statusBadge(complaint.status)
            + '<a class="btn btn-sm btn-outline-primary" href="/citizen/complaints/'
            + encodeURIComponent(complaint.referenceCode) + '">Open</a>'
            + '</div></div></div>';
    }

    async function load() {
        try {
            var complaints = await App.apiJson('/api/complaints/my');
            var active = complaints.filter(function (c) { return ACTIVE.indexOf(c.status) !== -1; }).length;
            document.getElementById('dashboardSummary').textContent =
                complaints.length + ' total — ' + active + ' active';
            document.getElementById('statCards').innerHTML =
                card('Total reports', complaints.length)
                + card('Active', active)
                + card('Resolved & closed', complaints.length - active);
            var empty = complaints.length === 0;
            document.getElementById('emptyState').hidden = !empty;
            document.getElementById('complaintList').innerHTML =
                complaints.map(row).join('');
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }

    document.addEventListener('DOMContentLoaded', load);
})();
