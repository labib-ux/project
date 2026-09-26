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
                } catch (e) { /* ignore */ }
                window.location.assign('/');
            });
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            initDrawer();
            initSignOut();
        });
    } else {
        initDrawer();
        initSignOut();
    }
})();
