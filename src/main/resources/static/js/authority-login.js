/* Authority sign-in: teal portal form, JWT straight to localStorage. */
(function () {
    'use strict';

    var form = document.querySelector('#authority-login-form');
    var feedback = document.querySelector('#authority-feedback');
    if (!form) return;

    document.querySelectorAll('[data-toggle-password]').forEach(function (button) {
        button.addEventListener('click', function () {
            var input = button.parentElement.querySelector('input');
            var show = input.type === 'password';
            input.type = show ? 'text' : 'password';
            button.textContent = show ? 'Hide' : 'Show';
        });
    });

    document.querySelectorAll('[data-demo-fill]').forEach(function (button) {
        button.addEventListener('click', function () {
            form.querySelector('[name="identifier"]').value = button.getAttribute('data-demo-fill');
            form.querySelector('[name="password"]').value = 'demo1234';
        });
    });

    form.addEventListener('submit', async function (event) {
        event.preventDefault();
        feedback.textContent = '';
        if (!form.reportValidity()) return;
        var values = Object.fromEntries(new FormData(form));
        var submitButton = form.querySelector('button[type="submit"]');
        submitButton.disabled = true;
        try {
            var res = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ identifier: values.identifier, password: values.password })
            });
            var result = await res.json().catch(function () { return null; });
            if (!res.ok || !result || !result.accessToken) {
                throw new Error((result && (result.message || result.detail)) || 'Sign in failed.');
            }
            var role = result.user && result.user.role;
            if (role !== 'DEPT_OFFICER' && role !== 'WARD_COUNCILOR' && role !== 'ADMIN') {
                throw new Error('This portal is for officers, councilors, and admins only.');
            }
            localStorage.setItem('nagorikSebaToken', result.accessToken);
            localStorage.setItem('nagorikSebaUser', JSON.stringify(result.user));
            window.location.assign('/authority/dashboard');
        } catch (error) {
            feedback.textContent = error.message;
        } finally {
            submitButton.disabled = false;
        }
    });
})();
