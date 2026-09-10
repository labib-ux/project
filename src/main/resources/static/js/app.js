/* Nagorik Seba shared frontend: JWT fetch wrapper + toast notifications.
 * Included on every interactive page. Depends on nothing. */
(function () {
    'use strict';

    var TOKEN_KEY = 'nagorikSebaToken';

    function getToken() {
        try {
            return localStorage.getItem(TOKEN_KEY);
        } catch (e) {
            return null;
        }
    }

    function toast(message, type) {
        var box = document.getElementById('app-toasts');
        if (!box) {
            box = document.createElement('div');
            box.id = 'app-toasts';
            box.setAttribute('aria-live', 'polite');
            document.body.appendChild(box);
        }
        var el = document.createElement('div');
        el.className = 'app-toast' + (type ? ' ' + type : '');
        el.textContent = message;
        box.appendChild(el);
        setTimeout(function () {
            if (el.parentNode) el.parentNode.removeChild(el);
        }, 4000);
    }

    function problemMessage(result, fallback) {
        if (!result) return fallback;
        if (result.fieldErrors) {
            var first = Object.values(result.fieldErrors)[0];
            if (first) return first;
        }
        return result.message || result.detail || fallback;
    }

    async function apiFetch(path, options) {
        var headers = Object.assign({}, (options && options.headers) || {});
        var token = getToken();
        if (token) headers['Authorization'] = 'Bearer ' + token;
        var res = await fetch(path, Object.assign({}, options, { headers: headers }));
        if (res.status === 401) {
            try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* ignore */ }
            var next = window.location.pathname + window.location.search;
            window.location.assign('/login?next=' + encodeURIComponent(next));
            throw new Error('Session expired. Please sign in again.');
        }
        return res;
    }

    async function apiJson(path, options) {
        var res = await apiFetch(path, options);
        var body = null;
        try {
            body = await res.json();
        } catch (e) {
            body = null;
        }
        if (!res.ok) {
            throw new Error(problemMessage(body, 'Request failed with status ' + res.status));
        }
        return body;
    }

    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    var STATUS_BADGE = {
        SUBMITTED: 'badge-SUBMITTED',
        VERIFIED: 'badge-VERIFIED',
        ASSIGNED: 'badge-ASSIGNED',
        IN_PROGRESS: 'badge-IN_PROGRESS',
        RESOLVED: 'badge-RESOLVED',
        CLOSED: 'badge-CLOSED',
        REOPENED: 'badge-REOPENED',
        REJECTED: 'badge-REJECTED',
        CANCELLED: 'badge-CANCELLED'
    };

    function statusBadge(status) {
        var cls = STATUS_BADGE[status] || 'badge-CLOSED';
        return '<span class="badge ' + cls + '">' + esc(status).replace(/_/g, ' ') + '</span>';
    }

    window.NagorikSeba = {
        getToken: getToken,
        toast: toast,
        apiFetch: apiFetch,
        apiJson: apiJson,
        esc: esc,
        statusBadge: statusBadge
    };
})();
