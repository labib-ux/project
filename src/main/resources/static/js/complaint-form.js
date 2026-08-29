const token = localStorage.getItem('nagorikSebaToken');
const complaintForm = document.querySelector('#complaint-form');

if (!token) {
    window.location.replace('/login?next=/citizen/complaint/new');
} else if (complaintForm) {
    const photosInput = document.querySelector('#photos');
    const photoList = document.querySelector('#photo-list');
    const feedback = document.querySelector('#complaint-feedback');
    const locationFeedback = document.querySelector('#location-feedback');
    const locateButton = document.querySelector('#locate-button');

    photosInput.addEventListener('change', () => {
        photoList.innerHTML = '';
        const files = Array.from(photosInput.files);
        if (files.length > 5) {
            feedback.textContent = 'Please choose no more than five photos.';
            feedback.className = 'complaint-feedback error';
            return;
        }
        files.forEach((file) => {
            const item = document.createElement('li');
            item.textContent = `${file.name} (${Math.ceil(file.size / 1024)} KB)`;
            photoList.append(item);
        });
    });

    locateButton.addEventListener('click', () => {
        if (!navigator.geolocation) {
            locationFeedback.textContent = 'Location services are not supported by this browser.';
            return;
        }
        locateButton.disabled = true;
        locationFeedback.textContent = 'Getting your location…';
        navigator.geolocation.getCurrentPosition(
            ({ coords }) => {
                complaintForm.elements.latitude.value = coords.latitude.toFixed(6);
                complaintForm.elements.longitude.value = coords.longitude.toFixed(6);
                locationFeedback.textContent = 'Location added.';
                locateButton.disabled = false;
            },
            () => {
                locationFeedback.textContent = 'We could not get your location. Enter the coordinates manually.';
                locateButton.disabled = false;
            },
            { enableHighAccuracy: true, timeout: 10000 }
        );
    });

    complaintForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        feedback.textContent = '';
        feedback.className = 'complaint-feedback';
        if (!complaintForm.reportValidity()) return;
        if (photosInput.files.length > 5) return;

        const submitButton = complaintForm.querySelector('button[type="submit"]');
        submitButton.disabled = true;
        try {
            const response = await fetch('/api/complaints', {
                method: 'POST',
                headers: { Authorization: `Bearer ${token}` },
                body: new FormData(complaintForm)
            });
            const result = await response.json();
            if (!response.ok) {
                const validationMessage = result.fieldErrors && Object.values(result.fieldErrors)[0];
                throw new Error(validationMessage || result.message || 'Could not submit the complaint.');
            }
            complaintForm.reset();
            photoList.innerHTML = '';
            feedback.textContent = `Report #${result.id} has been submitted. Its status is ${result.status.replace('_', ' ')}.`;
            feedback.classList.add('success');
        } catch (error) {
            feedback.textContent = error.message;
            feedback.classList.add('error');
        } finally {
            submitButton.disabled = false;
        }
    });
}
