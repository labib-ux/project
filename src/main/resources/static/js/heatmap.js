/* Public heatmap (T6, U10): Leaflet + heat layer, debounced bbox fetch, legend. */
(function () {
    'use strict';

    var map = null;
    var heatLayer = null;
    var markersLayer = null;
    var debounceTimer = null;

    var CATEGORY_COLORS = {
        ROADS: '#e74c3c',
        WATER_SUPPLY: '#3498db',
        ELECTRICITY: '#f1c40f',
        SANITATION: '#9b59b6',
        WATERLOGGING: '#1abc9c',
        MOSQUITO_BREEDING: '#e67e22',
        WASTE_MANAGEMENT: '#2ecc71',
        OTHER: '#95a5a6'
    };

    function categoryColor(category) {
        return CATEGORY_COLORS[category] || '#7f8c8d';
    }

    function currentMunicipality() {
        var el = document.getElementById('heatmapMunicipality');
        return el && el.value ? el.value : 'dhaka-north';
    }

    async function fetchHeatmap() {
        if (!map) return;
        var bounds = map.getBounds();
        var url = '/api/public/heatmap?municipality=' + encodeURIComponent(currentMunicipality())
            + '&minLng=' + bounds.getWest() + '&minLat=' + bounds.getSouth()
            + '&maxLng=' + bounds.getEast() + '&maxLat=' + bounds.getNorth();
        var res = await fetch(url);
        if (!res.ok) return;
        render(await res.json());
    }

    function render(data) {
        if (heatLayer) {
            map.removeLayer(heatLayer);
            heatLayer = null;
        }
        if (markersLayer) {
            markersLayer.clearLayers();
        } else if (window.L) {
            markersLayer = L.layerGroup().addTo(map);
        }
        if (!window.L) return;
        if (data.clustered) {
            heatLayer = L.heatLayer(
                data.points.map(function (cell) {
                    return [cell.lat, cell.lng, cell.count];
                }),
                {radius: 25, blur: 15}
            ).addTo(map);
        } else {
            data.points.forEach(function (point) {
                L.circleMarker([point.lat, point.lng], {
                    radius: 6,
                    color: categoryColor(point.category),
                    fillOpacity: 0.7
                }).bindPopup(point.category + ' — ' + point.status).addTo(markersLayer);
            });
        }
    }

    function scheduleFetch() {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(fetchHeatmap, 400);
    }

    function renderLegend() {
        var legend = document.getElementById('heatmapLegend');
        if (!legend) return;
        legend.innerHTML = Object.keys(CATEGORY_COLORS).map(function (category) {
            return '<span><i style="background:' + CATEGORY_COLORS[category] + '"></i> ' + category + '</span>';
        }).join(' ');
    }

    function initHeatmap() {
        var el = document.getElementById('heatmap');
        if (!el || !window.L) return;
        map = L.map('heatmap').setView([23.81, 90.41], 12);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '&copy; OpenStreetMap contributors'
        }).addTo(map);
        map.on('moveend', scheduleFetch);
        renderLegend();
        fetchHeatmap();
        var muni = document.getElementById('heatmapMunicipality');
        if (muni) muni.addEventListener('change', fetchHeatmap);
    }

    document.addEventListener('DOMContentLoaded', initHeatmap);
})();
