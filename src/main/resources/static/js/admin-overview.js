/* Admin overview cards. Externalised from admin/index.html (page CSP blocks inline scripts). */
(function () {
    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/admin');
        return;
    }
    function card(title, value) {
        return '<div class="col-md-3 mb-3"><div class="card"><div class="card-body">'
            + '<h6 class="text-muted text-uppercase">' + title + '</h6>'
            + '<p class="h3 mb-0">' + value + '</p></div></div></div>';
    }
    async function load() {
        try {
            var results = await Promise.all([
                App.apiJson('/api/admin/municipalities'),
                App.apiJson('/api/admin/departments'),
                App.apiJson('/api/admin/users'),
                App.apiJson('/api/admin/sla-policies')
            ]);
            document.getElementById('overviewCards').innerHTML =
                card('Municipalities', results[0].length)
                + card('Departments', results[1].length)
                + card('Users', results[2].length)
                + card('SLA policies', results[3].length);
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }
    document.addEventListener('DOMContentLoaded', load);
})();
