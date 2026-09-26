/* SLA policy administration. Externalised from admin/sla-policies.html (page CSP blocks inline scripts). */
(function () {
    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/admin/sla-policies');
        return;
    }
    function authHeaders() {
        return {
            'Authorization': 'Bearer ' + App.getToken(),
            'Content-Type': 'application/json'
        };
    }
    async function loadPolicies() {
        var mid = document.getElementById('municipalityId').value;
        var url = '/api/admin/sla-policies' + (mid ? '?municipalityId=' + encodeURIComponent(mid) : '');
        try {
            var policies = await App.apiJson(url);
            document.querySelector('#policyTable tbody').innerHTML = policies.map(function (p) {
                return '<tr><td>' + p.id + '</td><td>' + App.esc(p.category) + '</td><td>' + App.esc(p.priority) + '</td>'
                    + '<td><input type="number" min="1" value="' + p.maxHours + '" id="max-' + p.id + '" style="width:80px"></td>'
                    + '<td>' + (p.escalationLevel1Hours == null ? '—' : p.escalationLevel1Hours) + '</td>'
                    + '<td>' + (p.escalationLevel2Hours == null ? '—' : p.escalationLevel2Hours) + '</td>'
                    + '<td>' + p.active + '</td>'
                    + '<td><button class="btn btn-sm btn-primary" data-save-policy="' + p.id + '">Save</button></td></tr>';
            }).join('') || '<tr><td colspan="8">No policies</td></tr>';
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }
    async function savePolicy(id) {
        var maxHours = Number(document.getElementById('max-' + id).value);
        if (!maxHours || maxHours <= 0) {
            App.toast('maxHours must be positive.', 'error');
            return;
        }
        try {
            await App.apiJson('/api/admin/sla-policies/' + id, {
                method: 'PUT', headers: authHeaders(), body: JSON.stringify({ maxHours: maxHours })
            });
            App.toast('Policy updated.', 'success');
        } catch (error) {
            App.toast(error.message, 'error');
        }
        loadPolicies();
    }
    document.querySelector('#policyTable tbody').addEventListener('click', function (event) {
        var btn = event.target.closest('[data-save-policy]');
        if (btn) savePolicy(btn.getAttribute('data-save-policy'));
    });
    document.getElementById('loadBtn').addEventListener('click', loadPolicies);
    document.addEventListener('DOMContentLoaded', loadPolicies);
})();
