/* Municipality administration. Externalised from admin/municipalities.html (page CSP blocks inline scripts). */
(function () {
    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=/admin/municipalities');
        return;
    }
    function authHeaders() {
        return {
            'Authorization': 'Bearer ' + App.getToken(),
            'Content-Type': 'application/json'
        };
    }
    async function loadAll() {
        try {
            var munis = await App.apiJson('/api/admin/municipalities');
            document.querySelector('#muniTable tbody').innerHTML = munis.map(function (m) {
                return '<tr><td>' + m.id + '</td><td>' + App.esc(m.slug) + '</td><td>' + App.esc(m.name) + '</td><td>' + m.isActive + '</td></tr>';
            }).join('');
            var depts = await App.apiJson('/api/admin/departments');
            document.querySelector('#deptTable tbody').innerHTML = depts.map(function (d) {
                return '<tr><td>' + d.id + '</td><td>' + App.esc(d.code) + '</td><td>' + App.esc(d.name) + '</td>'
                    + '<td>' + App.esc((d.handlesCategories || []).join(', ')) + '</td><td>' + d.isActive + '</td></tr>';
            }).join('');
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }
    document.getElementById('wardForm').addEventListener('submit', async function (event) {
        event.preventDefault();
        var geojson;
        try {
            geojson = JSON.parse(document.getElementById('wGeoJson').value);
        } catch (e) {
            App.toast('GeoJSON is not valid JSON.', 'error');
            return;
        }
        try {
            await App.apiJson('/api/admin/wards/geojson', {
                method: 'POST',
                headers: authHeaders(),
                body: JSON.stringify({
                    municipalityId: Number(document.getElementById('wMunicipality').value),
                    wardNumber: Number(document.getElementById('wNumber').value),
                    areaName: document.getElementById('wName').value,
                    geojson: geojson
                })
            });
            App.toast('Ward added.', 'success');
            loadAll();
        } catch (error) {
            App.toast(error.message, 'error');
        }
    });
    document.addEventListener('DOMContentLoaded', loadAll);
})();
