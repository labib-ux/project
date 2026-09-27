/* Authority department queue: list + verify/assign/start/resolve actions.
 * External file: page CSP blocks inline scripts AND inline onclick attributes,
 * so row buttons use data-action delegation. */
(function () {
    'use strict';

    var App = window.NagorikSeba;
    var resolveTarget = null;

    function statusBadgeHtml(status) {
        var cls = 'badge-' + (status || 'SUBMITTED');
        return '<span class="badge-status ' + cls + '">' + (status || 'SUBMITTED') + '</span>';
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
            var ref = c.referenceCode;
            return '<tr>'
                + '<td class="py-2.5 px-3 font-bold text-on-surface"><a class="text-primary hover:underline" href="/authority/complaints/' + encodeURIComponent(ref) + '">' + App.esc(ref) + '</a></td>'
                + '<td class="py-2.5 px-3 font-sans font-medium">' + App.esc(c.title) + '</td>'
                + '<td class="py-2.5 px-3">' + statusBadgeHtml(c.status) + '</td>'
                + '<td class="py-2.5 px-3 text-secondary">' + App.esc(c.wardName || '—') + '</td>'
                + '<td class="py-2.5 px-3 text-right space-x-1 whitespace-nowrap">'
                + '<button class="bg-emerald-700 hover:bg-emerald-800 text-white px-2 py-1 uppercase text-[11px] font-bold" data-action="verify" data-ref="' + App.esc(ref) + '">Verify</button>'
                + '<button class="bg-primary-container hover:bg-blue-700 text-white px-2 py-1 uppercase text-[11px] font-bold" data-action="autoAssign" data-ref="' + App.esc(ref) + '">Assign</button>'
                + '<button class="bg-inverse-surface hover:bg-black text-white px-2 py-1 uppercase text-[11px] font-bold" data-action="start" data-ref="' + App.esc(ref) + '">Start</button>'
                + '<button class="bg-surface-container-lowest hover:bg-surface-container border border-emerald-700 text-emerald-800 px-2 py-1 uppercase text-[11px] font-bold" data-action="resolve" data-ref="' + App.esc(ref) + '">Resolve</button>'
                + '<a class="bg-surface-container-low hover:bg-surface-container border border-outline px-2 py-1 uppercase text-[11px] font-bold inline-block" href="/authority/complaints/' + encodeURIComponent(ref) + '">Dossier →</a>'
                + '</td></tr>';
        }).join('') || '<tr><td colspan="5" class="py-4 text-center text-secondary">No complaints found in current queue.</td></tr>';
    }

    async function verifyComplaint(ref) {
        try {
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(ref) + '/verify?note=Verified-from-queue', { method: 'POST' });
            App.toast('Verified ' + ref + '.', 'success');
        } catch (error) {
            App.toast(error.message, 'error');
        }
        loadQueue();
    }

    async function autoAssign(ref) {
        try {
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(ref) + '/assign/auto', { method: 'POST' });
            App.toast('Assigned ' + ref + ' to department.', 'success');
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
            await App.apiJson('/api/authority/complaints/' + encodeURIComponent(resolveTarget) + '/resolve', { method: 'POST', body: body });
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
