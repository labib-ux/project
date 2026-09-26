/* Citizen complaint detail: rating, reopen and withdraw actions + lightbox.
 * Externalised from citizen/complaint-detail.html (page CSP blocks inline
 * scripts). Depends on window.NagorikSeba (app.js) and loadDetail (timeline.js). */
(function () {
    var App = window.NagorikSeba;
    if (!App || !App.getToken()) {
        window.location.replace('/login?next=' + encodeURIComponent(window.location.pathname));
        return;
    }

    function ref() {
        var segments = window.location.pathname.split('/').filter(Boolean);
        if (segments.length >= 3 && segments[0] === 'citizen' && segments[1] === 'complaints') {
            return decodeURIComponent(segments[2]);
        }
        return new URLSearchParams(window.location.search).get('ref');
    }

    function idempotencyKey() {
        try {
            return (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now());
        } catch (e) {
            return String(Date.now());
        }
    }

    async function submitRate(event) {
        event.preventDefault();
        try {
            await App.apiJson('/api/complaints/' + encodeURIComponent(ref()) + '/rate?rating='
                + encodeURIComponent(document.getElementById('rating').value)
                + '&feedback=' + encodeURIComponent(document.getElementById('feedback').value),
                { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey() } });
            App.toast('Resolution ratified. Thank you for your civic participation!', 'success');
            loadDetail();
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }

    async function submitReopen(event) {
        event.preventDefault();
        try {
            await App.apiJson('/api/complaints/' + encodeURIComponent(ref()) + '/reopen?reason='
                + encodeURIComponent(document.getElementById('reason').value),
                { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey() } });
            App.toast('Docket reopened and SLA timer reset.', 'success');
            loadDetail();
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }

    async function submitCancel(event) {
        event.preventDefault();
        if (!confirm('Withdraw this complaint? This statutory action cannot be reversed.')) return;
        try {
            await App.apiJson('/api/complaints/' + encodeURIComponent(ref()) + '/cancel?reason='
                + encodeURIComponent(document.getElementById('cancelReason').value),
                { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey() } });
            App.toast('Complaint officially withdrawn.', 'success');
            loadDetail();
        } catch (error) {
            App.toast(error.message, 'error');
        }
    }

    function openLightbox(src) {
        document.getElementById('lightboxImg').src = src;
        document.getElementById('lightbox').classList.remove('hidden');
    }

    function closeLightbox() {
        document.getElementById('lightbox').classList.add('hidden');
        document.getElementById('lightboxImg').removeAttribute('src');
    }

    document.getElementById('rateForm').addEventListener('submit', submitRate);
    document.getElementById('reopenForm').addEventListener('submit', submitReopen);
    document.getElementById('cancelForm').addEventListener('submit', submitCancel);
    document.getElementById('lightboxClose').addEventListener('click', closeLightbox);
    document.getElementById('lightbox').addEventListener('click', function (event) {
        if (event.target.id === 'lightbox') closeLightbox();
    });
    document.getElementById('gallery').addEventListener('click', function (event) {
        var img = event.target.closest('img');
        if (img) openLightbox(img.src);
    });
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') closeLightbox();
    });

    loadDetail();
})();
