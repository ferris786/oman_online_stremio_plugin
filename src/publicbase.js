/**
 * Public base URL resolution.
 *
 * Priority: WAHAB_PUBLIC_URL env > RENDER_EXTERNAL_URL env (Render sets it
 * automatically) > the Host/X-Forwarded-Proto headers of the current request
 * (set by nginx/Caddy) > loopback fallback.
 *
 * On a VPS behind nginx there is no env var - the middleware observes the
 * request headers and remembers the public base for stream URL building
 * (which happens outside the request context).
 */
let envBase = process.env.RENDER_EXTERNAL_URL || process.env.WAHAB_PUBLIC_URL || null;
let observed = null;

module.exports = {
    initFromEnv() {
        if (process.env.WAHAB_PUBLIC_URL) envBase = process.env.WAHAB_PUBLIC_URL;
    },
    observe(req) {
        if (envBase) return;
        try {
            const proto = req.headers["x-forwarded-proto"] || req.protocol || "http";
            const host = req.headers["x-forwarded-host"] || req.headers.host;
            if (host) observed = `${proto}://${host}`;
        } catch (e) { /* ignore */ }
    },
    get() {
        return envBase || observed || `http://127.0.0.1:${process.env.PORT || 7000}`;
    }
};
