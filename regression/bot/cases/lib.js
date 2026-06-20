// Shared infrastructure for the use-case runner (regression/use-cases.md).
//
// Conventions lifted from compare.js / verify-rollback.js:
//  - RCON for server-truth and world setup (fills are unlogged).
//  - One mineflayer bot is both the actor and the query client; plugin
//    output is read from its chat stream.
//  - Server-truth checks via `execute if block` / `data get`, never a
//    plugin summary alone.
//  - Per-case plot isolation on a 64-block grid + tight t: windows, so
//    cases can't contaminate each other's queries.

import mineflayer from 'mineflayer';
import { Vec3 } from 'vec3';
import net from 'net';

// Ports are env-overridable so a throwaway test server can run alongside the
// live bench server without colliding (defaults match the bench setup).
export const HOST = process.env.SG_HOST || '127.0.0.1';
export const PORT = +(process.env.SG_PORT || 25566);
const RCON_PORT = +(process.env.SG_RCON_PORT || 25576), RCON_PASS = process.env.SG_RCON_PASS || 'test123';

export const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
export const log = (...a) => console.log(ts(), ...a);

// ── RCON ─────────────────────────────────────────────────────────
function packet(id, t, body) {
    const b = Buffer.from(body, 'utf8');
    const len = 4 + 4 + b.length + 2;
    const o = Buffer.alloc(4 + len);
    o.writeInt32LE(len, 0); o.writeInt32LE(id, 4); o.writeInt32LE(t, 8);
    b.copy(o, 12); o.writeInt16LE(0, 12 + b.length);
    return o;
}
export function rcon(cmd) {
    // Long responses (data get on a full chest) fragment across multiple
    // RCON packets - collect until the wire goes quiet, then concatenate.
    return new Promise((res, rej) => {
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 });
        let st = 0; let buf = Buffer.alloc(0); const bodies = [];
        let quietTimer = null;
        const finish = () => { s.end(); res(bodies.join('')); };
        s.on('error', rej);
        s.on('timeout', () => { s.destroy(); rej(new Error('rcon timeout')); });
        s.on('connect', () => s.write(packet(0x1337, 3, RCON_PASS)));
        s.on('data', c => {
            buf = Buffer.concat([buf, c]);
            while (buf.length >= 4) {
                const len = buf.readInt32LE(0);
                if (buf.length < len + 4) break;
                const id = buf.readInt32LE(4);
                const body = buf.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
                buf = buf.slice(len + 4);
                if (st === 0) {
                    if (id === -1) return rej('auth');
                    st = 1;
                    s.write(packet(0x1337, 2, cmd));
                } else {
                    bodies.push(body);
                    if (quietTimer) clearTimeout(quietTimer);
                    quietTimer = setTimeout(finish, 250);
                }
            }
        });
    });
}

// ── server truth ─────────────────────────────────────────────────
export async function blockIs(x, y, z, type) {
    const r = await rcon(`execute if block ${x} ${y} ${z} minecraft:${type}`);
    return /passed/i.test(r);
}
// Resolve the block at (x,y,z) against a candidate list; 'other' if none.
export async function blockAmong(x, y, z, candidates) {
    for (const c of candidates) {
        if (await blockIs(x, y, z, c)) return c;
    }
    return 'other';
}
// Block-entity NBT (chests, signs, jukeboxes…). Empty string when not a BE.
export async function blockData(x, y, z, path = '') {
    const r = await rcon(`data get block ${x} ${y} ${z}${path ? ' ' + path : ''}`);
    return /has the following|data:/i.test(r) || /\{|\[/.test(r) ? r : '';
}
export async function entityCountNear(x, y, z, type, dist = 8) {
    const r = await rcon(
        `execute positioned ${x} ${y} ${z} if entity @e[type=minecraft:${type},distance=..${dist}]`);
    const m = r.match(/passed.*?(\d+)?/i);
    if (!/passed/i.test(r)) return 0;
    return m && m[1] ? parseInt(m[1], 10) : 1;
}

// ── bot session ──────────────────────────────────────────────────
export async function startBot(name) {
    const bot = mineflayer.createBot({
        host: HOST, port: PORT, username: name, version: '1.21.4',
        checkTimeoutInterval: 180_000,
    });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${name}`);
    await rcon(`gamemode creative ${name}`);
    await sleep(800);
    // Rolling chat capture: every case interaction reads from this.
    bot.chatLog = [];
    bot.on('messagestr', m => {
        bot.chatLog.push(m);
        if (bot.chatLog.length > 600) bot.chatLog.splice(0, 200);
    });
    return bot;
}

export function waitForChat(bot, re, timeout) {
    return new Promise(resolve => {
        const handler = m => { if (re.test(m)) { bot.removeListener('messagestr', handler); resolve(m); } };
        bot.on('messagestr', handler);
        setTimeout(() => { bot.removeListener('messagestr', handler); resolve(null); }, timeout);
    });
}

// Send a command and collect every chat line until `quiet` ms pass with
// no new output (or `max` total). Returns the collected lines.
export async function chatCollect(bot, cmd, { quiet = 1800, max = 12000 } = {}) {
    const lines = [];
    let last = Date.now();
    const handler = m => { lines.push(m); last = Date.now(); };
    bot.on('messagestr', handler);
    bot.chat(cmd);
    const t0 = Date.now();
    while (Date.now() - last < quiet && Date.now() - t0 < max) await sleep(150);
    bot.removeListener('messagestr', handler);
    return lines;
}

// ── plugin wrappers ──────────────────────────────────────────────
// Spyglass search: returns collected lines; match() helper greps them.
export async function sgSearch(bot, query) {
    return chatCollect(bot, `/spyglass search ${query}`);
}
// CoreProtect lookup.
export async function coLookup(bot, params) {
    return chatCollect(bot, `/co lookup ${params}`, { quiet: 2500, max: 15000 });
}
export const grep = (lines, re) => lines.filter(l => re.test(l));

// Spyglass rollback/restore/undo with summary capture.
export async function sgOp(bot, kind, query, timeout = 90000) {
    const done = waitForChat(bot, /(reversals|No results|failed)/i, timeout);
    bot.chat(`/spyglass ${kind}${query ? ' ' + query : ''}`);
    const line = await done;
    await sleep(1200);
    const m = line && line.match(/(\d[\d,]*)\s+reversal/i);
    return { line: line || '(timeout)', applied: m ? parseInt(m[1].replace(/,/g, ''), 10) : 0 };
}
// CoreProtect rollback/restore with completion capture.
export async function coOp(bot, kind, params, timeout = 90000) {
    const done = waitForChat(bot, /(Rollback|Restore) (completed|finished)|No \w+ found/i, timeout);
    bot.chat(`/co ${kind} ${params}`);
    const line = await done;
    await sleep(1200);
    return { line: line || '(timeout)' };
}

// ── plot allocator ───────────────────────────────────────────────
// Cases get disjoint 16×16 plots on a 64-block grid, far from the perf
// benches (compare.js @14000, verify @13000, quick @11000).
const BASE_X = 16000, BASE_Z = 16000, Y = 80;
let plotIndex = 0;
export function nextPlot() {
    const i = plotIndex++;
    const x0 = BASE_X + (i % 32) * 64;
    const z0 = BASE_Z + Math.floor(i / 32) * 64;
    return { x0, z0, y: Y, x1: x0 + 15, z1: z0 + 15, cx: x0 + 8, cz: z0 + 8 };
}
export async function claimPlot(bot, plot) {
    await rcon(`forceload add ${plot.x0} ${plot.z0} ${plot.x1} ${plot.z1}`);
    // Floor to stand on + clean working volume above it (all unlogged).
    await rcon(`fill ${plot.x0} ${plot.y - 1} ${plot.z0} ${plot.x1} ${plot.y - 1} ${plot.z1} stone`);
    await rcon(`fill ${plot.x0} ${plot.y} ${plot.z0} ${plot.x1} ${plot.y + 8} ${plot.z1} air`);
    await rcon(`teleport ${bot.username} ${plot.cx + 0.5} ${plot.y} ${plot.cz + 0.5}`);
    await sleep(1500);
    try { await bot.waitForChunksToLoad(); } catch { }
    await sleep(500);
    // A bot's first dig after its first long-range teleport is rejected
    // until a place+dig round trip has synced the interaction state.
    // Prime it once per bot, on a scratch block outside the working area.
    if (!bot._interactionPrimed) {
        bot._interactionPrimed = true;
        const sx = plot.x0 + 1, sz = plot.z0 + 1;
        try {
            await rcon(`setblock ${sx} ${plot.y} ${sz} minecraft:stone`);
            await sleep(700);
            for (let i = 0; i < 3; i++) {
                const scratch = bot.blockAt(new Vec3(sx, plot.y, sz));
                if (scratch && scratch.name !== 'air') {
                    try {
                        await bot.lookAt(new Vec3(sx + 0.5, plot.y + 0.5, sz + 0.5), true);
                        await bot.dig(scratch);
                    } catch { }
                }
                await sleep(600);
                if (await blockIs(sx, plot.y, sz, 'air')) break;
            }
        } catch { }
        await rcon(`setblock ${sx} ${plot.y} ${sz} air`);
        await sleep(400);
    }
}
export async function releasePlot(plot) {
    await rcon(`kill @e[type=item,x=${plot.x0},z=${plot.z0},dx=16,dz=16,dy=64,y=${plot.y - 8}]`);
    await rcon(`forceload remove ${plot.x0} ${plot.z0} ${plot.x1} ${plot.z1}`);
}

// ── bot actions ──────────────────────────────────────────────────
export async function give(bot, item, count = 1) {
    await rcon(`give ${bot.username} minecraft:${item} ${count}`);
    await sleep(600);
}
export function held(bot, name) {
    return bot.inventory.items().find(i => i.name === name);
}
export async function equip(bot, name) {
    let item = held(bot, name);
    if (!item) { await give(bot, name); item = held(bot, name); }
    if (!item) throw new Error(`equip: no ${name} in inventory`);
    await bot.equip(item, 'hand');
    await sleep(300);
}
// Place `item` on top of the block at (x, y-1, z) so it lands at (x,y,z).
// Server-truth verified with one retry: the first interaction after a
// teleport can be rejected by stale position sync.
export async function placeAt(bot, item, x, y, z) {
    for (let attempt = 0; attempt < 2; attempt++) {
        await equip(bot, item);
        const ref = bot.blockAt(new Vec3(x, y - 1, z));
        if (!ref) { await sleep(1200); continue; }
        try {
            await bot.lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5), true);
            await bot.placeBlock(ref, new Vec3(0, 1, 0));
        } catch { /* verified below */ }
        await sleep(600);
        if (!(await blockIs(x, y, z, 'air'))) return;
        await sleep(800);
    }
    if (await blockIs(x, y, z, 'air')) {
        throw new Error(`placeAt: ${item} never landed at ${x},${y},${z}`);
    }
}
export async function digAt(bot, x, y, z) {
    let original = null;
    for (let attempt = 0; attempt < 2; attempt++) {
        const block = bot.blockAt(new Vec3(x, y, z));
        if (!block || block.name === 'air') { await sleep(1200); continue; }
        original = block.name;
        try {
            await bot.lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5), true);
            await bot.dig(block);
        } catch { /* verified below */ }
        await sleep(600);
        // Success = the original block type is gone (waterlogged blocks
        // leave water behind, not air).
        if (!(await blockIs(x, y, z, original))) return;
        await sleep(800);
    }
    if (original == null || await blockIs(x, y, z, original)) {
        throw new Error(`digAt: block at ${x},${y},${z} never broke server-side`);
    }
}
export async function lookAtBlock(bot, x, y, z) {
    await bot.lookAt(new Vec3(x + 0.5, y + 0.5, z + 0.5), true);
    await sleep(250);
}

// Wait for the Spyglass recorder (and CP's consumer) to drain so a
// just-acted event is queryable. SG drains ~250ms; CP batches ~1-3s.
export const drain = () => sleep(4000);

// ── verdicts ─────────────────────────────────────────────────────
export const PASS = 'pass', FAIL = 'fail', NA = 'n/a-capability', ERR = 'error', MAN = 'manual-deferred';
export function check(results, side, cond, okNote, failNote) {
    results[side].checks.push({ ok: !!cond, note: cond ? okNote : failNote });
    if (!cond) results[side].verdict = FAIL;
}
export function freshResult() {
    return {
        sg: { verdict: PASS, checks: [] },
        cp: { verdict: PASS, checks: [] },
        notes: [],
    };
}
