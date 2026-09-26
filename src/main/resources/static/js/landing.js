/* Landing page: live Dhaka complaint map, ward table, menu, toasts. */
(function () {
    'use strict';

    function toast(message, type) {
        var box = document.getElementById('landing-toasts');
        if (!box) return;
        var el = document.createElement('div');
        el.className = 'landing-toast' + (type ? ' ' + type : '');
        el.textContent = message;
        box.appendChild(el);
        setTimeout(function () {
            if (el.parentNode) el.parentNode.removeChild(el);
        }, 4000);
    }

    var hamburger = document.getElementById('landingHamburger');
    var mobileMenu = document.getElementById('landingMobileMenu');
    if (hamburger && mobileMenu) {
        hamburger.addEventListener('click', function () {
            var open = mobileMenu.classList.toggle('open');
            hamburger.setAttribute('aria-expanded', open ? 'true' : 'false');
        });
    }

    function dot(className) {
        return L.divIcon({
            className: '',
            html: '<span class="landing-dot ' + className + '"></span>',
            iconSize: [20, 20],
            iconAnchor: [10, 10],
            popupAnchor: [0, -10]
        });
    }

    function statusClass(status) {
        if (status === 'RESOLVED' || status === 'CLOSED') return 'landing-dot-resolved';
        return 'landing-dot-active';
    }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function loadMarkers(map) {
        var url = '/api/public/heatmap?municipality=dhaka-north'
            + '&minLng=90.36&minLat=23.72&maxLng=90.44&maxLat=23.88';
        fetch(url).then(function (res) {
            if (!res.ok) throw new Error('heatmap ' + res.status);
            return res.json();
        }).then(function (data) {
            (data.points || []).forEach(function (point) {
                if (data.clustered) {
                    // Grid cell: {lat, lng, count, category}
                    L.circleMarker([point.lat, point.lng], {
                        radius: 5 + Math.min(11, Math.sqrt(point.count || 1) * 2),
                        color: '#2563eb', fillOpacity: 0.55
                    }).addTo(map)
                        .bindPopup('<strong>' + esc(point.count || 1) + ' reports</strong>'
                            + '<span class="landing-popup-meta">' + esc(point.category || '') + ' · ~100m cell</span>');
                } else {
                    L.marker([point.lat, point.lng], { icon: dot(statusClass(point.status)) })
                        .addTo(map)
                        .bindPopup('<strong>' + esc(point.referenceCode) + '</strong>'
                            + '<span class="landing-popup-meta">' + esc(point.category)
                            + ' — ' + esc(point.status) + '</span>');
                }
            });
        }).catch(function () {
            toast('Live map data is unavailable right now.', 'error');
        });
    }

    function renderStats(rows) {
        var resolvedEl = document.getElementById('statResolved');
        var activeEl = document.getElementById('statActive');
        var avgEl = document.getElementById('statAvg');
        if (!resolvedEl || !rows || rows.length === 0) return;
        var resolved = rows.reduce(function (a, r) { return a + (r.resolvedComplaints || 0); }, 0);
        var total = rows.reduce(function (a, r) { return a + (r.totalComplaints || 0); }, 0);
        var hours = rows.filter(function (r) { return r.averageResolutionHours != null; })
            .map(function (r) { return r.averageResolutionHours; });
        resolvedEl.textContent = resolved.toLocaleString();
        activeEl.textContent = (total - resolved).toLocaleString();
        avgEl.textContent = hours.length === 0 ? '—'
            : (hours.reduce(function (a, h) { return a + h; }, 0) / hours.length / 24).toFixed(1);
    }

    function loadWards() {
        var body = document.getElementById('landingWardBody');
        var feedback = document.getElementById('landingFeedback');
        if (!body) return;
        fetch('/api/public/wards/scoreboard?municipality=dhaka-north').then(function (res) {
            if (!res.ok) throw new Error('scoreboard ' + res.status);
            return res.json();
        }).then(function (rows) {
            if (!rows || rows.length === 0) {
                body.innerHTML = '<tr><td colspan="5">No ward data for this period yet. <a href="/wards">Open the live map &rarr;</a></td></tr>';
                fallbackStatsFromHeatmap();
                return;
            }
            renderStats(rows);
            body.innerHTML = rows.slice(0, 8).map(function (row) {
                var pending = (row.totalComplaints || 0) - (row.resolvedComplaints || 0);
                var days = row.averageResolutionHours == null
                    ? '—' : (row.averageResolutionHours / 24).toFixed(1) + ' days';
                var rate = row.resolutionRate == null ? '—' : Number(row.resolutionRate).toFixed(1) + '%';
                return '<tr><td>Ward ' + esc(row.wardNumber) + ' - ' + esc(row.areaName) + '</td>'
                    + '<td><span class="landing-num-ok">' + esc(row.resolvedComplaints) + '</span></td>'
                    + '<td><span class="landing-num-bad">' + esc(pending) + '</span></td>'
                    + '<td>' + esc(rate) + '</td>'
                    + '<td>' + esc(days) + '</td></tr>';
            }).join('');
            if (feedback) feedback.textContent = 'Showing top ' + Math.min(8, rows.length) + ' of ' + rows.length + ' wards.';
        }).catch(function () {
            body.innerHTML = '<tr><td colspan="5">Ward performance data is unavailable right now.</td></tr>';
            toast('Ward performance data is unavailable right now.', 'error');
        });
    }

    /* Monthly snapshots only exist after the 1st-of-month job runs — on a
     * fresh database the scoreboard is empty, so derive hero counts from the
     * live heatmap instead of leaving "…" placeholders. */
    function fallbackStatsFromHeatmap() {
        var resolvedEl = document.getElementById('statResolved');
        var activeEl = document.getElementById('statActive');
        var avgEl = document.getElementById('statAvg');
        fetch('/api/public/heatmap?municipality=dhaka-north'
            + '&minLng=90.30&minLat=23.65&maxLng=90.55&maxLat=23.95').then(function (res) {
            if (!res.ok) throw new Error('heatmap ' + res.status);
            return res.json();
        }).then(function (data) {
            var points = data.points || [];
            var resolved = 0;
            var total = 0;
            points.forEach(function (p) {
                if (data.clustered) {
                    total += (p.count || 0);
                    resolved += (p.count || 0); // cells carry no status split; count as activity
                } else {
                    total += 1;
                    if (p.status === 'RESOLVED' || p.status === 'CLOSED') resolved += 1;
                }
            });
            if (resolvedEl) resolvedEl.textContent = resolved.toLocaleString();
            if (activeEl) activeEl.textContent = (total - resolved).toLocaleString();
            if (avgEl) avgEl.textContent = '—';
        }).catch(function () {
            if (resolvedEl) resolvedEl.textContent = '—';
            if (activeEl) activeEl.textContent = '—';
            if (avgEl) avgEl.textContent = '—';
        });
    }

    /* Reference-code tracker (CSP-safe submit listener; replaces inline onsubmit). */
    function initTracker() {
        var form = document.getElementById('trackForm');
        if (!form) return;
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var input = document.getElementById('trackInput');
            var code = input ? input.value.trim() : '';
            if (code) window.location.assign('/citizen/complaints/' + encodeURIComponent(code));
        });
    }

    function init() {
        initTracker();
        var el = document.getElementById('landing-map');
        if (!el || !window.L) return;
        var map = L.map('landing-map').setView([23.8103, 90.4125], 11);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);
        loadMarkers(map);
        loadWards();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
