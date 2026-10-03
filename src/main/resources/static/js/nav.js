/* Shared nav fragment behaviour (CSP-safe: no inline handlers).
 * Included from fragments/nav.html via <script th:src>. Runs on every
 * fragment-nav page. */
(function () {
    'use strict';

    function initDrawer() {
        var toggle = document.getElementById('mobileNavToggle');
        var drawer = document.getElementById('mobileNavDrawer');
        if (toggle && drawer) {
            toggle.addEventListener('click', function () {
                drawer.classList.toggle('hidden');
            });
        }
    }

    function initSignOut() {
        document.querySelectorAll('[data-signout]').forEach(function (link) {
            link.addEventListener('click', function (event) {
                event.preventDefault();
                try {
                    localStorage.removeItem('nagorikSebaToken');
                    localStorage.removeItem('nagorikSebaUser');
                    document.cookie = 'nagorikSebaToken=; Path=/; Max-Age=0; SameSite=Lax';
                } catch (e) { /* ignore */ }
                window.location.assign('/');
            });
        });
    }

    /* -------------------------------------------------------------------------
     * Notification bell.
     *
     * Loaded on every nav page. The badge is polled rather than pushed: there is
     * no websocket in this stack, and a 30s poll against a single indexed COUNT is
     * cheaper than holding a connection open per open tab — which matters when the
     * demo leaves a dashboard sitting in a second window.
     * ---------------------------------------------------------------------- */
    var POLL_MS = 30000;

    function renderBadge(App, count) {
        var badge = document.getElementById('notificationBadge');
        var bell = document.getElementById('notificationBell');
        if (!badge || !bell) return;
        badge.textContent = count > 99 ? '99+' : String(count);
        badge.classList.toggle('hidden', count === 0);
    }

    function renderList(App, items) {
        var list = document.getElementById('notificationList');
        if (!list) return;
        if (!items.length) {
            list.innerHTML = '<li class="px-4 py-6 text-center text-[11px] font-mono text-secondary">'
                + 'No notifications yet</li>';
            return;
        }
        list.innerHTML = items.map(function (n) {
            var tone = n.read ? 'text-secondary' : 'text-on-surface font-semibold';
            var dot = n.read ? ''
                : '<span class="mt-1.5 w-1.5 h-1.5 rounded-full bg-primary-container shrink-0"></span>';
            var code = n.referenceCode
                ? '<span class="font-mono text-[10px] text-primary-container">'
                    + App.esc(n.referenceCode) + '</span>'
                : '';
            return '<li class="px-4 py-3 flex gap-2 border-b border-outline-variant/40'
                + ' last:border-b-0 hover:bg-surface-container-low cursor-pointer"'
                + ' data-notification-id="' + n.id + '">'
                + dot
                + '<div class="min-w-0">'
                + '<div class="' + tone + ' text-[13px] leading-snug">'
                + App.esc(n.message) + '</div>'
                + '<div class="mt-1 flex items-center gap-2">' + code
                + '<span class="font-mono text-[10px] text-outline">'
                + App.esc(n.sentAt || '') + '</span>'
                + '</div></div></li>';
        }).join('');

        list.querySelectorAll('[data-notification-id]').forEach(function (row) {
            row.addEventListener('click', function () {
                openComplaint(items, row.getAttribute('data-notification-id'));
                markRead(row.getAttribute('data-notification-id'));
            });
        });
    }

    function openComplaint(items, id) {
        var match = items.filter(function (n) {
            return String(n.id) === String(id) && n.referenceCode;
        })[0];
        if (match) {
            window.location.assign('/authority/complaints/'
                + encodeURIComponent(match.referenceCode));
        }
    }

    function markRead(id) {
        var App = window.NagorikSeba;
        App.apiFetch('/api/notifications/' + id + '/read', { method: 'POST' })
            .then(function () { return refresh(); })
            .catch(function () { /* next poll reconciles the badge */ });
    }

    function refresh() {
        var App = window.NagorikSeba;
        return Promise.all([
            App.apiJson('/api/notifications/unread-count'),
            App.apiJson('/api/notifications?limit=15')
        ]).then(function (results) {
            renderBadge(App, results[0].count || 0);
            renderList(App, results[1] || []);
        }).catch(function () {
            // Signed out or restarting: stop polling instead of log-spamming a 401.
            if (!App.getToken() && typeof timer !== 'undefined') { clearInterval(timer); }
        });
    }

    function initBell() {
        var App = window.NagorikSeba;
        var bell = document.getElementById('notificationBell');
        if (!bell || !App || !App.getToken()) {
            return;
        }
        var panel = document.getElementById('notificationPanel');
        var markAllBtn = document.getElementById('notificationMarkAll');

        if (markAllBtn) {
            markAllBtn.addEventListener('click', function (event) {
                event.stopPropagation();
                App.apiFetch('/api/notifications/read-all', { method: 'POST' })
                    .then(function () { return refresh(); })
                    .catch(function (e) { App.toast(e.message, 'error'); });
            });
        }

        if (panel) {
            bell.addEventListener('click', function (event) {
                event.stopPropagation();
                panel.classList.toggle('hidden');
                if (!panel.classList.contains('hidden')) { refresh(); }
            });
            // Outside-click close: without it the panel stays open once opened,
            // which reads as broken rather than deliberate.
            document.addEventListener('click', function (event) {
                if (!panel.classList.contains('hidden') && !panel.contains(event.target)) {
                    panel.classList.add('hidden');
                }
            });
        }

        refresh();
        timer = setInterval(function () {
            if (!App.getToken()) { clearInterval(timer); return; }
            refresh();
        }, POLL_MS);
    }

    var timer = null;

    function initAll() {
        initDrawer();
        initSignOut();
        initBell();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initAll);
    } else {
        initAll();
    }
})();
