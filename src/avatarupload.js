/**
 * "Put your own picture on the TV" from a phone.
 *
 * The same shape as the TorBox pairing flow: the television shows a QR code
 * and a short code, the phone opens the page, picks a photo, and the TV
 * notices. Nothing is typed on a remote.
 *
 * The phone does the hard part. Its browser squares the photo off and
 * scales it to 512px on a canvas before sending, so a 5MB camera picture
 * arrives as a ~100KB avatar that is already the right shape. That also
 * means no multipart parser and no image library on the server.
 */

const fs = require("fs");
const path = require("path");

const DIR = path.join(__dirname, "../data/avatars");
const CODE_TTL_MS = 15 * 60 * 1000;
const MAX_BYTES = 3 * 1024 * 1024;

/** code -> { created, url } */
const codes = new Map();

function sweep() {
    const now = Date.now();
    for (const [c, v] of codes) if (now - v.created > CODE_TTL_MS) codes.delete(c);
}

function newCode() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let c;
    do {
        c = "";
        for (let i = 0; i < 6; i++) c += alphabet[Math.floor(Math.random() * alphabet.length)];
    } while (codes.has(c));
    return c;
}

function start() {
    sweep();
    const code = newCode();
    codes.set(code, { created: Date.now(), url: null });
    return code;
}

function valid(code) {
    sweep();
    return codes.has(String(code || "").toUpperCase());
}

function check(code) {
    sweep();
    const e = codes.get(String(code || "").toUpperCase());
    if (!e) return { status: "expired" };
    return e.url ? { status: "ready", url: e.url } : { status: "waiting" };
}

/** Accepts the phone's data URL, writes it, and marks the code done. */
function receive(code, dataUrl, who, base) {
    code = String(code || "").toUpperCase();
    const entry = codes.get(code);
    if (!entry) return { error: "That code has expired. Start again on the TV." };

    const m = /^data:image\/(png|jpeg|jpg|webp);base64,([A-Za-z0-9+/=]+)$/.exec(String(dataUrl || ""));
    if (!m) return { error: "That file was not an image" };
    const ext = m[1] === "jpeg" ? "jpg" : m[1];
    const buf = Buffer.from(m[2], "base64");
    if (buf.length > MAX_BYTES) return { error: "That picture is too large" };

    const safe = String(who || "avatar").toLowerCase().replace(/[^a-z0-9]+/g, "-").slice(0, 24)
        || "avatar";
    const name = `${safe}-${Date.now().toString(36)}.${ext}`;
    try {
        fs.mkdirSync(DIR, { recursive: true });
        fs.writeFileSync(path.join(DIR, name), buf);
    } catch (e) {
        return { error: "Could not save that picture" };
    }
    entry.url = `${base}/avatars/${encodeURIComponent(name)}`;
    return { ok: true, url: entry.url };
}

/** The phone page. One button, a preview, and it uploads on its own. */
function page(code) {
    return `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Wahab TV &middot; profile picture</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; background:#0e0e12; color:#d8d3c8;
         font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
         min-height:100dvh; display:flex; flex-direction:column; align-items:center;
         justify-content:center; padding:24px; text-align:center; }
  h1 { font-size:20px; letter-spacing:.18em; color:#e0b34d; margin:0 0 6px; font-weight:600; }
  p  { color:#9a9aa2; margin:0 0 28px; font-size:14px; }
  .disc { width:200px; height:200px; border-radius:50%; background:#1c1c24;
          border:2px dashed #3a3a46; display:grid; place-items:center;
          overflow:hidden; margin-bottom:26px; }
  .disc img { width:100%; height:100%; object-fit:cover; }
  .disc span { color:#5c5c68; font-size:13px; }
  label.btn, button { display:block; width:100%; max-width:320px; border:0;
          border-radius:12px; padding:17px 22px; font-size:17px; font-weight:700;
          background:#e0b34d; color:#1a1408; cursor:pointer; margin-bottom:12px; }
  button[disabled] { opacity:.45; }
  input[type=file] { display:none; }
  .msg { min-height:24px; font-size:14px; margin-top:10px; }
  .ok { color:#7E9B6B; } .bad { color:#d64b3a; }
</style></head><body>
  <h1>WAHAB TV</h1>
  <p>Pick a picture for your profile</p>
  <div class="disc" id="disc"><span>no picture yet</span></div>
  <label class="btn" for="f">Choose a photo</label>
  <input id="f" type="file" accept="image/*">
  <button id="send" disabled>Send to the TV</button>
  <div class="msg" id="msg"></div>
<script>
  var code = ${JSON.stringify(code)}, dataUrl = null;
  var disc = document.getElementById('disc'), msg = document.getElementById('msg'),
      send = document.getElementById('send');

  // Square off and shrink here rather than on the server: a phone photo is
  // several megabytes and the wrong shape for a round avatar.
  document.getElementById('f').addEventListener('change', function (e) {
    var file = e.target.files && e.target.files[0];
    if (!file) return;
    msg.className = 'msg'; msg.textContent = 'Preparing…';
    var img = new Image();
    img.onload = function () {
      var side = Math.min(img.width, img.height), out = 512;
      var c = document.createElement('canvas'); c.width = out; c.height = out;
      c.getContext('2d').drawImage(img, (img.width - side) / 2, (img.height - side) / 2,
                                   side, side, 0, 0, out, out);
      dataUrl = c.toDataURL('image/jpeg', 0.86);
      disc.innerHTML = '<img alt="" src="' + dataUrl + '">';
      send.disabled = false;
      msg.textContent = '';
      URL.revokeObjectURL(img.src);
    };
    img.onerror = function () { msg.className = 'msg bad'; msg.textContent = 'That file was not a picture'; };
    img.src = URL.createObjectURL(file);
  });

  send.addEventListener('click', function () {
    if (!dataUrl) return;
    send.disabled = true;
    msg.className = 'msg'; msg.textContent = 'Sending…';
    fetch('/avatar/upload/' + code, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ image: dataUrl })
    }).then(function (r) { return r.json(); }).then(function (j) {
      if (j && j.ok) {
        msg.className = 'msg ok';
        msg.textContent = 'Done. Look at the TV.';
      } else {
        msg.className = 'msg bad';
        msg.textContent = (j && j.error) || 'That did not work';
        send.disabled = false;
      }
    }).catch(function () {
      msg.className = 'msg bad'; msg.textContent = 'No connection'; send.disabled = false;
    });
  });
</script></body></html>`;
}

module.exports = { start, valid, check, receive, page };
