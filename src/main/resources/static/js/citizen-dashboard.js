/* Citizen dashboard: stat cards + vertical complaint list + empty state + FAB target. */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/citizen/dashboard');
        return;
    }

    var ACTIVE = ['SUBMITTED', 'VERIFIED', 'ASSIGNED', 'IN_PROGRESS', 'REOPENED'];
    var currentFilter = 'ALL';
    var allComplaints = [];

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

    function visibleComplaints() {
        if (currentFilter === 'ACTIVE') {
            return allComplaints.filter(function (c) { return ACTIVE.indexOf(c.status) !== -1; });
        }
        if (currentFilter === 'RESOLVED') {
            return allComplaints.filter(function (c) { return ACTIVE.indexOf(c.status) === -1; });
        }
        return allComplaints;
    }

    function renderList() {
        var complaints = visibleComplaints();
        var empty = complaints.length === 0;
        document.getElementById('emptyState').hidden = !empty;
        document.getElementById('complaintList').innerHTML =
            complaints.map(row).join('');
        document.querySelectorAll('#filterTabs button').forEach(function (button) {
            var on = button.dataset.filter === currentFilter;
            button.classList.toggle('btn-primary', on);
            button.classList.toggle('btn-outline-primary', !on);
        });
    }

    async function load() {
        try {
            allComplaints = await App.apiJson('/api/complaints/my');
            var active = allComplaints.filter(function (c) { return ACTIVE.indexOf(c.status) !== -1; }).length;
            document.getElementById('dashboardSummary').textContent =
                allComplaints.length + ' total — ' + active + ' active';
            document.getElementById('statCards').innerHTML =
                card('Total reports', allComplaints.length)
                + card('Active', active)
                + card('Resolved & closed', allComplaints.length - active);
            renderList();
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }

    document.querySelectorAll('#filterTabs button').forEach(function (button) {
        button.addEventListener('click', function () {
            currentFilter = button.dataset.filter;
            renderList();
        });
    });
    document.addEventListener('DOMContentLoaded', load);
})();
