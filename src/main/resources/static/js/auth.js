const form = document.querySelector('#auth-form');
const feedback = document.querySelector('#auth-feedback');

if (form) {
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        feedback.textContent = '';
        feedback.className = 'auth-feedback';

        if (!form.reportValidity()) return;

        const register = form.dataset.register === 'true';
        const values = Object.fromEntries(new FormData(form));
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
            feedback.textContent = `Welcome, ${result.user.fullName}. Your account is ready.`;
            feedback.classList.add('success');
        } catch (error) {
            feedback.textContent = error.message;
            feedback.classList.add('error');
        } finally {
            submitButton.disabled = false;
        }
    });
}
