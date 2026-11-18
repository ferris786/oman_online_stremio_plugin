/**
 * Households: two or more installs of the app that know about each other.
 *
 * Three things are built on one link:
 *   - an inbox, so a title can be sent to the other person and waits for
 *     them (this is the part that gets used, because being on the app at
 *     the same moment is rare)
 *   - presence, so you can see what the other person is watching now
 *   - a room, so you can watch the same thing in step
 *
 * Joining reuses the short-code handshake the TorBox phone pairing already
 * uses: one TV shows a code, the other types it.
 *
 * What persists and what does not is deliberate. Houses and inboxes are
 * written to disk, because losing a sent title or a link on a restart
 * would be a real fault. Presence and rooms are memory only: they describe
 * right now, and a stale room after a restart is worse than no room.
 */

const fs = require("fs");
const path = require("path");

const STORE = path.join(__dirname, "../data/houses.json");

const MAX_MEMBERS = 6;
const MAX_INBOX = 50;
const CODE_TTL_MS = 15 * 60 * 1000;     // a join code is good for 15 min
const PRESENCE_TTL_MS = 90 * 1000;      // older than this is "not watching"
const ROOM_TTL_MS = 6 * 60 * 60 * 1000; // a forgotten room expires

/** houseId -> { id, created, members: [{deviceId, name, joined}], inbox: {deviceId: [item]} } */
let houses = {};
/** code -> { houseId, expires } */
const codes = new Map();
/** houseId -> { deviceId: {name, videoId, title, ..., at} } */
const presence = new Map();
/** houseId -> room state */
const rooms = new Map();

// --------------------------------------------------------------- persistence

function load() {
    try {
        houses = JSON.parse(fs.readFileSync(STORE, "utf-8")) || {};
    } catch (e) {
        houses = {};
    }
}
load();

let saveTimer = null;
function save() {
    // debounced: a burst of joins or sends should cost one write
    if (saveTimer) return;
    saveTimer = setTimeout(() => {
        saveTimer = null;
        try {
            fs.mkdirSync(path.dirname(STORE), { recursive: true });
            fs.writeFileSync(STORE, JSON.stringify(houses));
        } catch (e) {
            console.warn("[house] save failed:", e.message);
        }
    }, 400);
}

// --------------------------------------------------------------- helpers

function clip(v, n = 300) {
    if (v === null || v === undefined) return "";
    return String(v).replace(/[\r\n]+/g, " ").slice(0, n);
}

function newId() {
    return "h" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

/** no 0/O/1/I - these get read off a television across a room */
function newCode() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let code;
    do {
        code = "";
        for (let i = 0; i < 6; i++) code += alphabet[Math.floor(Math.random() * alphabet.length)];
    } while (codes.has(code));
    return code;
}

function sweepCodes() {
    const now = Date.now();
    for (const [code, v] of codes) if (v.expires < now) codes.delete(code);
}

function member(house, deviceId) {
    return (house.members || []).find(m => m.deviceId === deviceId);
}

// --------------------------------------------------------------- the link

/** Create a house around its first member and return a code for the other TV. */
function start(deviceId, name) {
    deviceId = clip(deviceId, 64);
    if (!deviceId) throw new Error("deviceId required");
    sweepCodes();

    // already in one? reuse it, so pressing the button twice does not
    // strand the person in a second empty house
    let house = Object.values(houses).find(h => member(h, deviceId));
    if (!house) {
        house = {
            id: newId(),
            created: Date.now(),
            members: [{ deviceId, name: clip(name, 40) || "Viewer", joined: Date.now() }],
            inbox: {}
        };
        houses[house.id] = house;
        save();
    }
    const code = newCode();
    codes.set(code, { houseId: house.id, expires: Date.now() + CODE_TTL_MS });
    return { code, houseId: house.id, members: house.members.map(m => m.name) };
}

/** Join an existing house with the code shown on the other TV. */
function join(code, deviceId, name) {
    sweepCodes();
    const entry = codes.get(String(code || "").toUpperCase().trim());
    if (!entry) return { error: "That code is wrong or has expired" };
    const house = houses[entry.houseId];
    if (!house) return { error: "That household no longer exists" };

    deviceId = clip(deviceId, 64);
    if (!deviceId) return { error: "deviceId required" };

    const existing = member(house, deviceId);
    if (existing) {
        existing.name = clip(name, 40) || existing.name;
    } else {
        if (house.members.length >= MAX_MEMBERS) return { error: "That household is full" };
        house.members.push({ deviceId, name: clip(name, 40) || "Viewer", joined: Date.now() });
    }
    save();
    return { houseId: house.id, members: house.members.map(m => m.name) };
}

function leave(houseId, deviceId) {
    const house = houses[houseId];
    if (!house) return { ok: true };
    house.members = (house.members || []).filter(m => m.deviceId !== deviceId);
    delete (house.inbox || {})[deviceId];
    // an empty house is rubbish; drop it along with anything hanging off it
    if (house.members.length === 0) {
        delete houses[houseId];
        presence.delete(houseId);
        rooms.delete(houseId);
    }
    save();
    return { ok: true };
}

function info(houseId, deviceId) {
    const house = houses[houseId];
    if (!house) return { error: "unknown household" };
    return {
        houseId: house.id,
        me: (member(house, deviceId) || {}).name || null,
        members: house.members.map(m => ({ deviceId: m.deviceId, name: m.name })),
        others: house.members.filter(m => m.deviceId !== deviceId).map(m => m.name)
    };
}

// --------------------------------------------------------------- the inbox

/**
 * Send a title to everyone else in the house. This is the asynchronous
 * half: it waits for them rather than needing them to be watching.
 */
function send(houseId, fromDeviceId, item) {
    const house = houses[houseId];
    if (!house) return { error: "unknown household" };
    const from = member(house, fromDeviceId);
    if (!from) return { error: "not a member of that household" };
    if (!item || !item.id || !item.kind) return { error: "item needs a kind and an id" };

    const entry = {
        sentId: Date.now().toString(36) + Math.random().toString(36).slice(2, 5),
        from: from.name,
        at: Date.now(),
        note: clip(item.note, 140),
        kind: clip(item.kind, 20),
        id: clip(item.id, 120),
        name: clip(item.name, 200),
        poster: clip(item.poster, 400),
        background: clip(item.background, 400),
        year: clip(item.year, 10)
    };

    house.inbox = house.inbox || {};
    let delivered = 0;
    for (const m of house.members) {
        if (m.deviceId === fromDeviceId) continue;
        const list = house.inbox[m.deviceId] || [];
        // re-sending the same title replaces the old note instead of stacking
        const without = list.filter(x => !(x.kind === entry.kind && x.id === entry.id));
        without.unshift(entry);
        house.inbox[m.deviceId] = without.slice(0, MAX_INBOX);
        delivered++;
    }
    save();
    return { ok: true, delivered, to: house.members.filter(m => m.deviceId !== fromDeviceId).map(m => m.name) };
}

function inbox(houseId, deviceId) {
    const house = houses[houseId];
    if (!house) return { error: "unknown household" };
    // checked for consistency with send/presence/room: a device that is not
    // a member gets told so rather than a plausible-looking empty inbox
    if (!member(house, deviceId)) return { error: "not a member of that household" };
    return { items: ((house.inbox || {})[deviceId] || []) };
}

/** Taking it off the row is the viewer's choice, so removal is explicit. */
function dismiss(houseId, deviceId, sentId) {
    const house = houses[houseId];
    if (!house) return { error: "unknown household" };
    if (!member(house, deviceId)) return { error: "not a member of that household" };
    house.inbox = house.inbox || {};
    const list = house.inbox[deviceId] || [];
    house.inbox[deviceId] = sentId === "all" ? [] : list.filter(x => x.sentId !== sentId);
    save();
    return { ok: true, remaining: house.inbox[deviceId].length };
}

// --------------------------------------------------------------- presence

/** What this device is watching right now. Overwrites; never accumulates. */
function beat(houseId, deviceId, state) {
    const house = houses[houseId];
    if (!house) return { error: "unknown household" };
    const m = member(house, deviceId);
    if (!m) return { error: "not a member of that household" };

    const map = presence.get(houseId) || {};
    if (state && state.watching === false) {
        delete map[deviceId];
    } else {
        map[deviceId] = {
            name: m.name,
            kind: clip(state && state.kind, 20),
            videoId: clip(state && state.videoId, 120),
            seriesId: clip(state && state.seriesId, 120),
            title: clip(state && state.title, 200),
            poster: clip(state && state.poster, 400),
            background: clip(state && state.background, 400),
            posS: Math.max(0, parseInt(state && state.posS) || 0),
            durS: Math.max(0, parseInt(state && state.durS) || 0),
            playing: !!(state && state.playing),
            at: Date.now()
        };
    }
    presence.set(houseId, map);
    return { ok: true };
}

/** Everyone except the caller, and only if they are genuinely current. */
function watching(houseId, deviceId) {
    const house = houses[houseId];
    if (!house) return { error: "unknown household" };
    const map = presence.get(houseId) || {};
    const now = Date.now();
    const out = [];
    for (const [id, p] of Object.entries(map)) {
        if (id === deviceId) continue;
        if (now - p.at > PRESENCE_TTL_MS) continue;
        out.push(Object.assign({ deviceId: id, ageS: Math.round((now - p.at) / 1000) }, p));
    }
    return { watching: out };
}

// --------------------------------------------------------------- the room

/**
 * Watching in step.
 *
 * The room holds the agreed position and, importantly, who is currently
 * stalled. The rule that makes this bearable lives in the client: if
 * ANYONE is stalled, everyone waits. Hiding one person's buffering is what
 * makes every other watch-together feature drift apart.
 *
 * The room pins the CONTENT and the chosen source descriptor, not a
 * playable URL, because each TV resolves its own link through its own
 * TorBox account.
 */
function roomOpen(houseId, deviceId, body) {
    const house = houses[houseId];
    if (!house) return { error: "unknown household" };
    const m = member(house, deviceId);
    if (!m) return { error: "not a member of that household" };

    const room = {
        houseId,
        host: deviceId,
        hostName: m.name,
        kind: clip(body && body.kind, 20),
        videoId: clip(body && body.videoId, 120),
        seriesId: clip(body && body.seriesId, 120),
        title: clip(body && body.title, 200),
        poster: clip(body && body.poster, 400),
        background: clip(body && body.background, 400),
        // how the other TV finds the same file for itself
        source: {
            name: clip(body && body.source && body.source.name, 120),
            hash: clip(body && body.source && body.source.hash, 80),
            fileIdx: parseInt(body && body.source && body.source.fileIdx),
            epTag: clip(body && body.source && body.source.epTag, 40),
            dn: clip(body && body.source && body.source.dn, 300)
        },
        positionMs: Math.max(0, parseInt(body && body.positionMs) || 0),
        paused: !!(body && body.paused),
        stalled: {},
        opened: Date.now(),
        updated: Date.now(),
        seq: 1
    };
    if (!Number.isFinite(room.source.fileIdx)) room.source.fileIdx = -1;
    rooms.set(houseId, room);
    return { ok: true, room: publicRoom(room) };
}

function publicRoom(room) {
    const now = Date.now();
    const stalledBy = Object.entries(room.stalled || {})
        .filter(([, v]) => now - v.at < 12000)   // a stall report goes stale fast
        .map(([, v]) => v.name);
    return {
        host: room.host, hostName: room.hostName,
        kind: room.kind, videoId: room.videoId, seriesId: room.seriesId,
        title: room.title, poster: room.poster, background: room.background,
        source: room.source,
        positionMs: room.positionMs,
        paused: room.paused,
        stalledBy,
        waiting: stalledBy.length > 0,
        seq: room.seq,
        ageS: Math.round((now - room.updated) / 1000)
    };
}

/**
 * One call does both directions: it publishes this device's state and
 * returns the room's. Two seconds of polling is plenty for a television
 * and keeps the client simple.
 */
function roomSync(houseId, deviceId, body) {
    const room = rooms.get(houseId);
    if (!room) return { error: "no room" };
    if (Date.now() - room.opened > ROOM_TTL_MS) {
        rooms.delete(houseId);
        return { error: "no room" };
    }
    const house = houses[houseId];
    const m = house && member(house, deviceId);
    if (!m) return { error: "not a member of that household" };

    // stall reporting, from anyone in the room
    room.stalled = room.stalled || {};
    if (body && body.stalled) room.stalled[deviceId] = { name: m.name, at: Date.now() };
    else delete room.stalled[deviceId];

    // Whoever acts owns the clock: a pause, a resume or a seek from either
    // side moves the room. Ordinary position reports from a non-host are
    // ignored, otherwise two clients fight over a drifting value.
    const acted = body && body.control;
    if (acted || deviceId === room.host) {
        if (body && body.positionMs !== undefined) {
            room.positionMs = Math.max(0, parseInt(body.positionMs) || 0);
        }
        if (body && body.paused !== undefined) room.paused = !!body.paused;
        if (acted) { room.host = deviceId; room.hostName = m.name; }
        room.seq++;
    }
    room.updated = Date.now();
    return { ok: true, room: publicRoom(room) };
}

function roomGet(houseId) {
    const room = rooms.get(houseId);
    if (!room) return { error: "no room" };
    return { room: publicRoom(room) };
}

function roomClose(houseId, deviceId) {
    const room = rooms.get(houseId);
    if (room) rooms.delete(houseId);
    return { ok: true };
}

module.exports = {
    start, join, leave, info,
    send, inbox, dismiss,
    beat, watching,
    roomOpen, roomSync, roomGet, roomClose
};
