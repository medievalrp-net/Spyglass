// Proof-of-work verification: does /spyglass rollback ACTUALLY change
// blocks in the world, or does it just report a fast "done"?
//
// A rollback whose completion regex matches "No results" would look
// blazing fast while changing nothing — so timing alone can't prove the
// engine works. This script reads the REAL block state at sample points
// across three stages and shows it changing:
//
//   1. fill stone (RCON, unlogged)        → samples should read STONE
//   2. //replace stone air (WE, logged)   → samples should read AIR
//   3. /spyglass rollback (restore)        → samples should read STONE again
//
// Two independent readers per sample:
//   - bot.blockAt()  : the literal block name the bot sees (client view)
//   - execute if block: server-side ground truth over RCON
// Plus the rollback summary's APPLIED count (RollbackService only counts
// a reversal as "applied" when it genuinely changed a world block —
// already-correct blocks are "skipped"). applied > 0 == real writes.

import mineflayer from 'mineflayer';
import { Vec3 } from 'vec3';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

// ── RCON (color-code-stripping, lifted from compare.js) ────────────
function packet(id, t, body) {
    const b = Buffer.from(body, 'utf8');
    const len = 4 + 4 + b.length + 2;
    const o = Buffer.alloc(4 + len);
    o.writeInt32LE(len, 0); o.writeInt32LE(id, 4); o.writeInt32LE(t, 8);
    b.copy(o, 12); o.writeInt16LE(0, 12 + b.length);
    return o;
}
function rcon(cmd) {
    return new Promise((res, rej) => {
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 });
        let st = 0; const bufs = [];
        s.on('error', rej);
        s.on('timeout', () => { s.destroy(); rej(new Error('rcon timeout')); });
        s.on('connect', () => s.write(packet(0x1337, 3, PASS)));
        s.on('data', c => {
            bufs.push(c);
            const all = Buffer.concat(bufs);
            if (all.length < 4) return;
            const len = all.readInt32LE(0);
            if (all.length < len + 4) return;
            const id = all.readInt32LE(4);
            const body = all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
            if (st === 0) { if (id === -1) return rej('auth'); st = 1; bufs.length = 0; s.write(packet(0x1337, 2, cmd)); }
            else { s.end(); res(body); }
        });
    });
}
function waitForChat(bot, re, timeout) {
    return new Promise(resolve => {
        const handler = m => { if (re.test(m)) { bot.removeListener('messagestr', handler); resolve(m); } };
        bot.on('messagestr', handler);
        setTimeout(() => { bot.removeListener('messagestr', handler); resolve(null); }, timeout);
    });
}

// Server-side ground truth: ask the server whether the block equals a
// given type. `execute if block` prints "Test passed" / "Test failed".
async function serverBlockIs(x, y, z, type) {
    const r = await rcon(`execute if block ${x} ${y} ${z} minecraft:${type}`);
    return /passed/i.test(r);
}
async function serverRead(x, y, z) {
    if (await serverBlockIs(x, y, z, 'stone')) return 'stone';
    if (await serverBlockIs(x, y, z, 'air')) return 'air';
    return 'other';
}

const BOT = 'vrf' + Date.now().toString(36).slice(-4);
// 16³ = 4096 blocks, clear of compare.js (14000) and quick-rb (11000).
const SIDE = 16;
const X0 = 13000, Y0 = 80, Z0 = 13000;
const X1 = X0 + SIDE - 1, Y1 = Y0 + SIDE - 1, Z1 = Z0 + SIDE - 1;

// Sample the 8 corners + the centre.
const SAMPLES = [
    [X0, Y0, Z0], [X1, Y0, Z0], [X0, Y1, Z0], [X1, Y1, Z0],
    [X0, Y0, Z1], [X1, Y0, Z1], [X0, Y1, Z1], [X1, Y1, Z1],
    [X0 + 8, Y0 + 8, Z0 + 8],
];

async function readAll(bot) {
    const out = [];
    for (const [x, y, z] of SAMPLES) {
        const server = await serverRead(x, y, z);
        const b = bot.blockAt(new Vec3(x, y, z));
        out.push({ x, y, z, server, client: b ? b.name : 'null' });
    }
    return out;
}
function fmtStage(rows) {
    return rows.map(r => `(${r.x},${r.y},${r.z}) srv=${r.server} cli=${r.client}`);
}

(async () => {
    log(`Verifying rollback writes with bot ${BOT} on a ${SIDE}³=${SIDE ** 3}-block cube @ ${X0},${Y0},${Z0}`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(800);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`);
    await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${X0 + 8.5} ${Y1 + 6} ${Z0 + 8.5}`);
    await sleep(2500);
    try { await bot.waitForChunksToLoad(); } catch { }
    await sleep(1000);

    // ── Stage 1: fill stone (unlogged) ────────────────────────────
    log('Stage 1: /fill stone (RCON, unlogged)…');
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y1} ${Z1} stone`);
    await sleep(1500);
    const afterFill = await readAll(bot);

    // ── Stage 2: //replace stone air (logged by Spyglass) ─────────
    log('Stage 2: //replace stone air (WorldEdit, logged)…');
    bot.chat('//sel cuboid'); await sleep(300);
    bot.chat(`//pos1 ${X0},${Y0},${Z0}`); await sleep(300);
    bot.chat(`//pos2 ${X1},${Y1},${Z1}`); await sleep(300);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 60000);
    bot.chat('//replace stone air');
    const replMsg = await replaceDone;
    log(`  WE: ${(replMsg || '(no replace confirmation)').trim()}`);
    await sleep(3000);  // let the recorder drain the break events
    const afterReplace = await readAll(bot);

    // ── Stage 3: /spyglass rollback (restore air → stone) ─────────
    log('Stage 3: /spyglass rollback p:' + BOT + ' t:5m -g …');
    let summary = null;
    const rbHandler = m => { if (/(reversals|No results)/i.test(m)) summary = m; };
    bot.on('messagestr', rbHandler);
    const t0 = Date.now();
    bot.chat(`/spyglass rollback p:${BOT} t:5m -g`);
    while (summary == null && Date.now() - t0 < 60000) await sleep(200);
    bot.removeListener('messagestr', rbHandler);
    await sleep(2000);
    const afterRollback = await readAll(bot);

    // ── Stage 4: /spyglass undo (reverse the rollback → air) ──────
    // The undo replays the rollback's inverse-effect ledger in chunks
    // (#17); world truth must come back to the post-//replace state.
    log('Stage 4: /spyglass undo …');
    let undoSummary = null;
    const undoHandler = m => { if (/(reversals|no valid actions|Undo failed)/i.test(m)) undoSummary = m; };
    bot.on('messagestr', undoHandler);
    const tUndo = Date.now();
    bot.chat('/spyglass undo');
    while (undoSummary == null && Date.now() - tUndo < 60000) await sleep(200);
    bot.removeListener('messagestr', undoHandler);
    await sleep(2000);
    const afterUndo = await readAll(bot);

    // ── Report ────────────────────────────────────────────────────
    log('\n──────── BLOCK STATE BY STAGE (srv = server truth, cli = bot view) ────────');
    for (let i = 0; i < SAMPLES.length; i++) {
        const f = afterFill[i], r = afterReplace[i], b = afterRollback[i], u = afterUndo[i];
        log(`(${f.x},${f.y},${f.z})  fill:srv=${f.server}/cli=${f.client}  ->  replace:srv=${r.server}/cli=${r.client}  ->  rollback:srv=${b.server}/cli=${b.client}  ->  undo:srv=${u.server}/cli=${u.client}`);
    }
    log('');
    log(`Rollback summary line: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(none / timeout)'}`);
    log(`Rollback wall time: ${Date.now() - t0} ms`);
    log(`Undo summary line: ${undoSummary ? undoSummary.replace(/\s+/g, ' ').trim() : '(none / timeout)'}`);
    log(`Undo wall time: ${Date.now() - tUndo} ms`);

    // ── Verdict ───────────────────────────────────────────────────
    const allStoneAfterFill = afterFill.every(s => s.server === 'stone');
    const allAirAfterReplace = afterReplace.every(s => s.server === 'air');
    const allStoneAfterRollback = afterRollback.every(s => s.server === 'stone');
    const allAirAfterUndo = afterUndo.every(s => s.server === 'air');
    const appliedMatch = summary && summary.match(/(\d[\d,]*)\s+reversal/i);
    const applied = appliedMatch ? parseInt(appliedMatch[1].replace(/,/g, ''), 10) : 0;
    const undoneMatch = undoSummary && undoSummary.match(/(\d[\d,]*)\s+reversal/i);
    const undone = undoneMatch ? parseInt(undoneMatch[1].replace(/,/g, ''), 10) : 0;

    log('\n──────── VERDICT ────────');
    log(`  all samples STONE after fill:        ${allStoneAfterFill ? 'YES' : 'NO'}`);
    log(`  all samples AIR after //replace:     ${allAirAfterReplace ? 'YES' : 'NO'}`);
    log(`  all samples STONE after rollback:    ${allStoneAfterRollback ? 'YES' : 'NO'}  <-- rollback wrote blocks`);
    log(`  all samples AIR after undo:          ${allAirAfterUndo ? 'YES' : 'NO'}  <-- undo reversed them`);
    log(`  rollback reported reversals:         ${applied.toLocaleString()}`);
    log(`  undo reported reversals:             ${undone.toLocaleString()}`);
    const pass = allStoneAfterFill && allAirAfterReplace && allStoneAfterRollback
        && allAirAfterUndo && applied > 0 && undone === applied;
    log(`\n  RESULT: ${pass ? 'PASS — rollback restored the blocks and undo reversed them.' : 'FAIL — see stages above.'}`);

    bot.quit();
    await sleep(1500);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(pass ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
