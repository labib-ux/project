/* Authority department queue: list + verify/assign/start/resolve actions.
 * Externalised from authority/queue.html (page CSP blocks inline scripts AND
 * inline onclick attributes, so row buttons use data-action delegation). */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    var resolveTarget = null;

    function esc(value) {
        return (App ? App.esc(value) : String(value == null ? '' : value));
    }

    async function loadQueue() {
        if (!App || !App.getToken()) {
            window.location.replace('/login/authority?next=/authority/queue');
            return;
        }
        var mid = document.getElementById('municipalityId').value;
        if (!mid) {
            App.toast('Enter a municipality ID, or sign in again so it fills automatically.', 'error');
            return;
        }
        var statuses = document.getElementById('statusFilter').value.trim();
        var url = '/api/authority/queue?municipalityId=' + encodeURIComponent(mid);
        if (statuses) {
            statuses.split(',').forEach(function (s) { url += '&status=' + encodeURIComponent(s.trim()); });
        }
        var items;
        try {
            items = await App.apiJson(url);
        } catch (error) {
            App.toast(error.message, 'error');
            return;
        }
        document.querySelector('#queueTable tbody').innerHTML = items.map(function (c) {
            return '<tr><td>' + esc(c.referenceCode) + '</td><td>' + esc(c.title) + '</td><td>' + esc(c.status) + '</td>'
                + '<td>' + esc(c.wardName || '—') + '</td>'
                + '<td><button class="btn btn-sm btn-success" data-action="verify" data-ref="' + esc(c.referenceCode) + '">Verify</button> '
                + '<button class="btn btn-sm btn-primary" data-action="autoAssign" data-ref="' + esc(c.referenceCode) + '">Auto-assign</button> '
                + '<button class="btn btn-sm btn-secondary" data-action="start" data-ref="' + esc(c.referenceCode) + '">Start</button> '
                + '<button class="btn btn-sm btn-outline-success" data-action="resolve" data-ref="' + esc(c.referenceCode) + '">Resolve</button></td></tr>';
        }).join('') || '<tr><td colspan="5">No complaints</td></tr>';
    }

    async function verifyComplaint(ref) {
        try {
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(ref) + '/verify?note=Verified-from-queue',
                { method: 'POST' });
            App.toast('Verified ' + ref + '.', 'success');
        } catch (error) {
            App.toast(error.message, 'error');
        }
        loadQueue();
    }

    async function autoAssign(ref) {
        try {
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(ref) + '/assign/auto', { method: 'POST' });
            App.toast('Assigned ' + ref + '.', 'success');
        } catch (error) {
            App.toast(error.message, 'error');
        }
        loadQueue();
    }

    async function startWork(ref) {
        try {
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(ref) + '/start', { method: 'POST' });
            App.toast('Work started on ' + ref + '.', 'success');
        } catch (error) {
            App.toast(error.message, 'error');
        }
        loadQueue();
    }

    function openResolve(ref) {
        resolveTarget = ref;
        document.getElementById('resolveRef').textContent = ref;
        document.getElementById('resolveFeedback').textContent = '';
        document.getElementById('resolvePhotos').value = '';
        document.getElementById('resolveModal').classList.remove('hidden');
    }

    document.querySelector('#queueTable tbody').addEventListener('click', function (event) {
        var btn = event.target.closest('[data-action]');
        if (!btn) return;
        var ref = btn.getAttribute('data-ref');
        var action = btn.getAttribute('data-action');
        if (action === 'verify') verifyComplaint(ref);
        else if (action === 'autoAssign') autoAssign(ref);
        else if (action === 'start') startWork(ref);
        else if (action === 'resolve') openResolve(ref);
    });
    document.getElementById('resolveCancel').addEventListener('click', function () {
        document.getElementById('resolveModal').classList.add('hidden');
    });
    document.getElementById('resolveConfirm').addEventListener('click', async function () {
        var photos = document.getElementById('resolvePhotos').files;
        if (!photos || photos.length < 1) {
            document.getElementById('resolveFeedback').textContent = 'A work-proof photo is required.';
            return;
        }
        var body = new FormData();
        body.append('note', document.getElementById('resolveNote').value);
        body.append('photos', photos[0]);
        try {
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(resolveTarget) + '/resolve',
                { method: 'POST', body: body });
            App.toast('Resolved ' + resolveTarget + '.', 'success');
            document.getElementById('resolveModal').classList.add('hidden');
        } catch (error) {
            document.getElementById('resolveFeedback').textContent = error.message;
        }
        loadQueue();
    });
    document.getElementById('loadBtn').addEventListener('click', loadQueue);
    (function prefillMunicipality() {
        try {
            var user = JSON.parse(localStorage.getItem('nagorikSebaUser') || 'null');
            var mids = user && user.municipalityIds ? Array.from(user.municipalityIds) : [];
            if (mids.length === 1) {
                document.getElementById('municipalityId').value = mids[0];
                loadQueue();
            }
        } catch (e) { /* leave manual entry */ }
    })();
})();
