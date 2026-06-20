// Heterogeneous before/after distribution check for rollback fidelity.
// Builds a cube whose every Y-layer is a different block type (cycling 8
// types), griefs it with //set air (LOGGED), rolls back, then verifies —
// PER LAYER, server-side — that each layer came back 100% its own type.
// Per-layer (not just per-type aggregate) catches a wrong-type write or an
// equal-count swap that a uniform or aggregate test would miss.
// Usage: node _distribution-proof.js [SIDE]
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

const SIDE = parseInt(process.argv[2] || '64', 10);
const X0 = 26000, Y0 = 72, Z0 = 26000;
const X1 = X0 + SIDE - 1, Y1 = Y0 + SIDE - 1, Z1 = Z0 + SIDE - 1;
const VOL = SIDE * SIDE * SIDE;
const LAYER = SIDE * SIDE;
const CX0 = X0 >> 4, CZ0 = Z0 >> 4, CX1 = X1 >> 4, CZ1 = Z1 >> 4;
// 8 full, solid, simple block types (no gravity/fluid/tile-entity); none is
// the diamond_block marker used for counting.
const TYPES = ['stone', 'dirt', 'cobblestone', 'oak_planks', 'glass', 'gold_block', 'bricks', 'sandstone'];
const typeOf = y => TYPES[(y - Y0) % TYPES.length];

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
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 120000 });
        let st = 0; const bufs = [];
        s.on('error', rej);
        s.on('timeout', () => { s.destroy(); rej(new Error('rcon timeout: ' + cmd)); });
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
const countOf = s => { const m = s && s.match(/(\d[\d,]*)\s+block/i); return m ? parseInt(m[1].replace(/,/g, ''), 10) : 0; };

// Count `type` over the whole cube via type->diamond_block, then restore.
async function countKeep(type) {
    let n = 0;
    for (let cx = CX0; cx <= CX1; cx++) for (let cz = CZ0; cz <= CZ1; cz++) {
        const bx0 = Math.max(X0, cx << 4), bx1 = Math.min(X1, (cx << 4) + 15);
        const bz0 = Math.max(Z0, cz << 4), bz1 = Math.min(Z1, (cz << 4) + 15);
        n += countOf(await rcon(`fill ${bx0} ${Y0} ${bz0} ${bx1} ${Y1} ${bz1} diamond_block replace ${type}`));
    }
    for (let cx = CX0; cx <= CX1; cx++) for (let cz = CZ0; cz <= CZ1; cz++) {
        const bx0 = Math.max(X0, cx << 4), bx1 = Math.min(X1, (cx << 4) + 15);
        const bz0 = Math.max(Z0, cz << 4), bz1 = Math.min(Z1, (cz << 4) + 15);
        await rcon(`fill ${bx0} ${Y0} ${bz0} ${bx1} ${Y1} ${bz1} ${type} replace diamond_block`);
    }
    return n;
}
function waitChat(bot, re, timeout) {
    return new Promise(resolve => {
        const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); resolve(m); } };
        bot.on('messagestr', h);
        setTimeout(() => { bot.removeListener('messagestr', h); resolve(null); }, timeout);
    });
}

const BOT = 'rbd' + Date.now().toString(36).slice(-4);

(async () => {
    log(`Region ${SIDE}^3 = ${VOL.toLocaleString()} blocks @ (${X0},${Y0},${Z0}); ${TYPES.length} types in Y-layers`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(1500);

    // Stage 0: lay each Y-layer as its own type (RCON, UNLOGGED). One /fill
    // per layer (SIDE*SIDE <= 32768 for SIDE<=181).
    log('Stage 0: build the layered mix (RCON, unlogged)…');
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y1} ${Z1} air`);
    const expected = {};
    for (let y = Y0; y <= Y1; y++) {
        const t = typeOf(y);
        await rcon(`fill ${X0} ${y} ${Z0} ${X1} ${y} ${Z1} ${t}`);
        expected[t] = (expected[t] || 0) + LAYER;
    }
    log('  expected distribution (by construction):');
    for (const t of TYPES) log(`     ${t.padEnd(12)} ${expected[t].toLocaleString()}`);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`);
    await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${X0 + SIDE / 2} ${Y1 + 4} ${Z0 + SIDE / 2}`);
    await sleep(2500);
    try { await bot.waitForChunksToLoad(); } catch { }
    await sleep(1500);

    log('Stage 1: bot //set air over the cube (LOGGED, Y-slabbed)…');
    let weAffected = 0;
    for (let ys = Y0; ys <= Y1; ys += 16) {
        const ye = Math.min(Y1, ys + 15);
        bot.chat('//sel cuboid'); await sleep(150);
        bot.chat(`//pos1 ${X0},${ys},${Z0}`); await sleep(150);
        bot.chat(`//pos2 ${X1},${ye},${Z1}`); await sleep(150);
        const done = waitChat(bot, /(blocks? have been changed|operation completed|affected)/i, 180000);
        bot.chat('//set air');
        const msg = await done;
        const m = msg && msg.match(/(\d[\d,]*)/);
        if (m) weAffected += parseInt(m[1].replace(/,/g, ''), 10);
        await sleep(300);
    }
    log(`  WorldEdit //set air affected ${weAffected.toLocaleString()} blocks; cube is now air`);
    await sleep(8000);

    log('Stage 2: /sg rollback p:' + BOT + ' t:1h -g …');
    let summary = null;
    const h = m => { if (/(reversals|No results|rolled back|applied)/i.test(m)) summary = m; };
    bot.on('messagestr', h);
    const t0 = Date.now();
    bot.chat(`/sg rollback p:${BOT} t:1h -g`);
    while (summary == null && Date.now() - t0 < 180000) await sleep(200);
    bot.removeListener('messagestr', h);
    log(`  rollback: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(timeout)'}  (${Date.now() - t0}ms)`);
    await sleep(5000);

    // Stage 3: PER-LAYER verify — each layer must be 100% its own type.
    // Destructive (replaces the expected type with diamond per layer); we're
    // done after this. A layer short of SIDE^2 means a wrong-type / missing
    // write at some position in that layer.
    log('Stage 3: per-layer verification (server-side /fill replace)…');
    const after = {};
    let badLayers = 0;
    let worstLayer = null;
    for (let y = Y0; y <= Y1; y++) {
        const t = typeOf(y);
        const n = countOf(await rcon(`fill ${X0} ${y} ${Z0} ${X1} ${y} ${Z1} diamond_block replace ${t}`));
        after[t] = (after[t] || 0) + n;
        if (n !== LAYER) {
            badLayers++;
            if (worstLayer == null || n < worstLayer.n) worstLayer = { y, t, n };
        }
    }
    const totalCorrect = Object.values(after).reduce((a, b) => a + b, 0);

    log('\n──────── DISTRIBUTION: expected vs after rollback ────────');
    let allMatch = true;
    for (const t of TYPES) {
        const ok = (after[t] || 0) === expected[t];
        if (!ok) allMatch = false;
        log(`  ${t.padEnd(12)} expected ${expected[t].toLocaleString().padStart(9)}  after ${(after[t] || 0).toLocaleString().padStart(9)}  ${ok ? 'OK' : 'MISMATCH'}`);
    }
    log(`  ${'TOTAL'.padEnd(12)} expected ${VOL.toLocaleString().padStart(9)}  after ${totalCorrect.toLocaleString().padStart(9)}`);
    log(`  layers verified: ${SIDE - badLayers}/${SIDE} fully correct` + (badLayers ? `  (worst: y=${worstLayer.y} ${worstLayer.t} ${worstLayer.n}/${LAYER})` : ''));
    const pass = allMatch && totalCorrect === VOL && badLayers === 0;
    log(`\n  VERDICT: ${pass
        ? 'PASS — every layer restored to its exact type; distribution identical before and after.'
        : 'FAIL — distribution diverged (see mismatches / bad layers above).'}`);

    bot.quit();
    await sleep(1000);
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y1} ${Z1} air`);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(pass ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
