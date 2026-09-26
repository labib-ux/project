/* Tailwind Play CDN theme tokens for Nagorik Seba.
 * External file (not inline) so the page Content-Security-Policy stays
 * strict. Must load AFTER https://cdn.tailwindcss.com — the Play CDN reads
 * `tailwind.config` lazily and regenerates utilities on change. */
tailwind.config = {
    darkMode: "class",
    theme: {
        extend: {
            colors: {
                "primary": "#004ac6",
                "primary-container": "#2563eb",
                "on-primary": "#ffffff",
                "on-primary-container": "#eeefff",
                "secondary": "#565e74",
                "secondary-container": "#dae2fd",
                "on-secondary": "#ffffff",
                "surface": "#faf8ff",
                "surface-dim": "#d2d9f4",
                "surface-bright": "#faf8ff",
                "surface-container-lowest": "#ffffff",
                "surface-container-low": "#f2f3ff",
                "surface-container": "#eaedff",
                "surface-container-high": "#e2e7ff",
                "surface-container-highest": "#dae2fd",
                "on-surface": "#131b2e",
                "on-surface-variant": "#434655",
                "outline": "#737686",
                "outline-variant": "#c3c6d7",
                "inverse-surface": "#283044",
                "inverse-on-surface": "#eef0ff",
                "error": "#ba1a1a",
                "error-container": "#ffdad6",
                "on-error": "#ffffff",
                "on-error-container": "#93000a",
                "background": "#faf8ff",
                "on-background": "#131b2e"
            },
            fontFamily: {
                "sans": ["Public Sans", "-apple-system", "BlinkMacSystemFont", "sans-serif"],
                "mono": ["JetBrains Mono", "monospace"],
                "headline-sm": ["Public Sans"],
                "headline-md": ["Public Sans"],
                "headline-lg": ["Public Sans"],
                "data-mono-sm": ["JetBrains Mono"],
                "data-mono-md": ["JetBrains Mono"],
                "data-mono-lg": ["JetBrains Mono"]
            }
        }
    }
};
