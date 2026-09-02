// Map Picker for Complaint Form
// Click on map to set lat/lng, calls /api/public/wards/lookup to display ward name

let map;
let marker;
let debounceTimer;

document.addEventListener('DOMContentLoaded', function() {
    initMap();
});

function initMap() {
    // Default to Dhaka, Bangladesh
    const defaultLat = 23.8103;
    const defaultLng = 90.4125;

    map = L.map('map').setView([defaultLat, defaultLng], 12);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
    }).addTo(map);

    map.on('click', function(e) {
        setMarker(e.latlng.lat, e.latlng.lng);
        updateHiddenFields(e.latlng.lat, e.latlng.lng);
        lookupWard(e.latlng.lat, e.latlng.lng);
    });

    // Try to get user's location
    if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(function(position) {
            const lat = position.coords.latitude;
            const lng = position.coords.longitude;
            map.setView([lat, lng], 15);
            setMarker(lat, lng);
            updateHiddenFields(lat, lng);
            lookupWard(lat, lng);
        }, function() {
            // Silently fail if geolocation is denied
        });
    }
}

function setMarker(lat, lng) {
    if (marker) {
        marker.setLatLng([lat, lng]);
    } else {
        marker = L.marker([lat, lng], { draggable: true }).addTo(map);
        marker.on('dragend', function(e) {
            const pos = e.target.getLatLng();
            updateHiddenFields(pos.lat, pos.lng);
            lookupWard(pos.lat, pos.lng);
        });
    }
    map.panTo([lat, lng]);
}

function updateHiddenFields(lat, lng) {
    document.getElementById('latitude').value = lat.toFixed(8);
    document.getElementById('longitude').value = lng.toFixed(8);
}

function lookupWard(lat, lng) {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(function() {
        fetch(`/api/public/wards/lookup?lat=${lat}&lng=${lng}`)
            .then(response => {
                if (response.ok) {
                    return response.json();
                }
                return null;
            })
            .then(data => {
                const wardInfo = document.getElementById('wardInfo');
                if (data && data.areaName) {
                    wardInfo.innerHTML = '<i class="fas fa-map-marker-alt me-1"></i> Ward: ' + data.areaName + 
                        (data.areaNameBn ? ' (' + data.areaNameBn + ')' : '') +
                        (data.wardNumber ? ' - Ward ' + data.wardNumber : '');
                    wardInfo.className = 'mt-2 text-success small';
                } else {
                    wardInfo.innerHTML = '<i class="fas fa-exclamation-triangle me-1"></i> No ward found for this location';
                    wardInfo.className = 'mt-2 text-warning small';
                }
            })
            .catch(error => {
                console.error('Ward lookup failed:', error);
                const wardInfo = document.getElementById('wardInfo');
                wardInfo.innerHTML = '<i class="fas fa-exclamation-circle me-1"></i> Ward lookup unavailable';
                wardInfo.className = 'mt-2 text-danger small';
            });
    }, 300);
}