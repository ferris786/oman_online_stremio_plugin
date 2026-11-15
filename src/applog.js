/**
 * Device log sink.
 *
 * The TV is the only place that knows why playback stopped, and there is
 * no way to read its logcat remotely. The app posts small batches of
 * events here; they are appended to a file on the VPS so a fault can be
 * read back after the fact instead of guessed at.
 *
 * Writing is open (the payload is harmless and the server is low profile,
 * and this repo is public so any shared secret in the app would leak).
 * READING needs WAHAB_LOG_KEY, which only exists in the systemd unit.
 */

const fs = require("fs");
const path = require("path");

const LOG_DIR = path.join(__dirname, "../data");
const LOG_FILE = path.join(LOG_DIR, "app.log");
const MAX_BYTES = 8 * 1024 * 1024;      // rotate at 8 MB, keep one old file
const MAX_EVENTS = 200;                  // per POST
const MAX_FIELD = 400;                   // per string

function clip(v) {
    if (v === null || v === undefined) return "";
    return String(v).replace(/[\r\n]+/g, " ").slice(0, MAX_FIELD);
}

function rotate() {
    try {
        const st = fs.statSync(LOG_FILE);
        if (st.size > MAX_BYTES) fs.renameSync(LOG_FILE, LOG_FILE + ".1");
    } catch (e) { /* no file yet */ }
}

function write(entry) {
    try {
        fs.mkdirSync(LOG_DIR, { recursive: true });
        rotate();
        fs.appendFileSync(LOG_FILE, JSON.stringify(entry) + "\n");
    } catch (e) {
        console.warn("[applog] write failed:", e.message);
    }
}

/** POST body -> lines on disk. Never throws. */
function ingest(body, ip) {
    const device = clip(body && body.device);
    const app = clip(body && body.app);
    const profile = clip(body && body.profile);
    const events = Array.isArray(body && body.events) ? body.events.slice(0, MAX_EVENTS) : [];
    const now = new Date().toISOString();
    for (const e of events) {
        write({
            at: now,
            t: clip(e && e.t),            // the device's own timestamp
            dev: device,
            app,
            prof: profile,
            lvl: clip(e && e.level) || "info",
            tag: clip(e && e.tag),
            msg: clip(e && e.msg),
            data: e && e.data && typeof e.data === "object"
                ? Object.fromEntries(Object.entries(e.data).slice(0, 20).map(([k, v]) => [clip(k), clip(v)]))
                : undefined
        });
    }
    return events.length;
}

function tail(n, filter) {
    try {
        const raw = fs.readFileSync(LOG_FILE, "utf-8").trimEnd().split("\n");
        let lines = raw;
        if (filter) {
            const f = String(filter).toLowerCase();
            lines = lines.filter(l => l.toLowerCase().includes(f));
        }
        return lines.slice(-Math.min(n || 200, 2000));
    } catch (e) {
        return [];
    }
}

module.exports = { ingest, tail, LOG_FILE };
