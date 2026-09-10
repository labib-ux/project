const form = document.querySelector('#auth-form');
const feedback = document.querySelector('#auth-feedback');

const BD_PHONE_RE = /^01[3-9]\d{8}$/;
const INTL_PHONE_RE = /^(?:\+8801|8801|01)[3-9]\d{8}$/;

function fail(message) {
    feedback.textContent = message;
    feedback.className = 'auth-feedback error';
}

document.querySelectorAll('[data-toggle-password]').forEach((button) => {
    button.addEventListener('click', () => {
        const input = button.parentElement.querySelector('input');
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        button.textContent = show ? 'Hide' : 'Show';
    });
});

document.querySelectorAll('[data-demo-fill]').forEach((button) => {
    button.addEventListener('click', () => {
        const identifier = button.getAttribute('data-demo-fill');
        const register = form && form.dataset.register === 'true';
        if (register) {
            const email = form.querySelector('[name="email"]');
            if (email) email.value = identifier;
        } else {
            const field = form.querySelector('[name="identifier"]');
            if (field) field.value = identifier;
        }
        const password = form.querySelector('[name="password"]');
        if (password) password.value = 'demo1234';
    });
});

if (form) {
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        feedback.textContent = '';
        feedback.className = 'auth-feedback';

        if (!form.reportValidity()) return;

        const register = form.dataset.register === 'true';
        const values = Object.fromEntries(new FormData(form));
        if (register) {
            if (values.phone && !INTL_PHONE_RE.test(values.phone.trim())) {
                fail('Enter a valid Bangladeshi mobile number, e.g. 01712345678.');
                return;
            }
            if (values.password !== values.confirmPassword) {
                fail('Passwords do not match.');
                return;
            }
        }
        const payload = register
            ? { fullName: values.fullName, email: values.email, phone: values.phone || null, password: values.password }
            : { identifier: values.identifier, password: values.password };
        const submitButton = form.querySelector('button[type="submit"]');
        submitButton.disabled = true;

        try {
            const response = await fetch(register ? '/api/auth/register' : '/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const result = await response.json();
            if (!response.ok) {
                const validationMessage = result.fieldErrors && Object.values(result.fieldErrors)[0];
                throw new Error(validationMessage || result.message || 'Something went wrong. Please try again.');
            }
            localStorage.setItem('nagorikSebaToken', result.accessToken);
            localStorage.setItem('nagorikSebaUser', JSON.stringify(result.user));
            feedback.textContent = `Welcome, ${result.user.fullName}. Taking you to the complaint form…`;
            feedback.classList.add('success');
            const next = new URLSearchParams(window.location.search).get('next') || '/citizen/complaint/new';
            window.setTimeout(() => { window.location.assign(next); }, 550);
        } catch (error) {
            feedback.textContent = error.message;
            feedback.classList.add('error');
        } finally {
            submitButton.disabled = false;
        }
    });
}
