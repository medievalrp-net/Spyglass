// Independent before/after COUNT of a //set-air → /sg rollback cycle.
//
// Goal: prove whether the rollback ACTUALLY restores every block, not
// trust the plugin's "applied" number. All counts come from vanilla
// /fill ... replace over RCON (server-side ground truth), never Spyglass.
//
// Flow:
//   1. /fill stone over the whole cube via RCON (UNLOGGED baseline).
//   2. bot //set air over the cube (LOGGED by Spyglass -> break events).
//   3. bot /sg rollback p:<bot> r:150 t:1h        (should restore stone).
//   4. Count, per chunk, how much stone came back vs air left vs other:
//        stone_restored = sum over chunks of  /fill glass replace stone
//        air_remaining  =                     /fill bedrock replace air
//        corrupted      = VOL - stone_restored - air_remaining
//
// A perfect rollback => stone_restored == VOL, air_remaining == 0.

import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

// ── region: SIDE³, placed remote & high so it's pure air to start ──
const SIDE = 64;
const X0 = 20000, Y0 = 70, Z0 = 20000;
const X1 = X0 + SIDE - 1, Y1 = Y0 + SIDE - 1, Z1 = Z0 + SIDE - 1;
const VOL = SIDE * SIDE * SIDE;
const CX0 = X0 >> 4, CZ0 = Z0 >> 4, CX1 = X1 >> 4, CZ1 = Z1 >> 4;

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

// /fill <block> [replace <filter>] over the whole cube, tiled into
// 8-high slabs (64*64*8 = 32768 = the vanilla /fill cap). Returns the
// summed "filled N blocks" count.
async function fillCube(block, filter) {
    let total = 0;
    for (let y = Y0; y <= Y1; y += 8) {
        const yb = Math.min(y + 7, Y1);
        const r = await rcon(`fill ${X0} ${y} ${Z0} ${X1} ${yb} ${Z1} ${block}${filter ? ` replace ${filter}` : ''}`);
        total += countOf(r);
    }
    return total;
}
// Per-chunk stone count (16x16x64 = 16384 <= cap). Converts stone->glass.
async function stonePerChunk() {
    const rows = [];
    for (let cx = CX0; cx <= CX1; cx++) {
        for (let cz = CZ0; cz <= CZ1; cz++) {
            const bx0 = Math.max(X0, cx << 4), bx1 = Math.min(X1, (cx << 4) + 15);
            const bz0 = Math.max(Z0, cz << 4), bz1 = Math.min(Z1, (cz << 4) + 15);
            const r = await rcon(`fill ${bx0} ${Y0} ${bz0} ${bx1} ${Y1} ${bz1} glass replace stone`);
            rows.push({ cx, cz, stone: countOf(r) });
        }
    }
    return rows;
}
function waitChat(bot, re, timeout) {
    return new Promise(resolve => {
        const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); resolve(m); } };
        bot.on('messagestr', h);
        setTimeout(() => { bot.removeListener('messagestr', h); resolve(null); }, timeout);
    });
}

const BOT = 'rbc' + Date.now().toString(36).slice(-4);

(async () => {
    log(`Region ${SIDE}³ = ${VOL} blocks @ (${X0},${Y0},${Z0})..(${X1},${Y1},${Z1}); chunks x[${CX0}..${CX1}] z[${CZ0}..${CZ1}]`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(1500);

    log('Stage 0: /fill stone baseline (RCON, unlogged)…');
    await fillCube('air');               // clean slate
    const filled = await fillCube('stone');
    log(`  baseline stone written: ${filled} (expect ${VOL})`);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`);
    await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${X0 + SIDE / 2} ${Y1 + 4} ${Z0 + SIDE / 2}`);
    await sleep(2500);
    try { await bot.waitForChunksToLoad(); } catch { }
    await sleep(1500);

    log('Stage 1: bot //set air over the cube (LOGGED)…');
    bot.chat('//sel cuboid'); await sleep(400);
    bot.chat(`//pos1 ${X0},${Y0},${Z0}`); await sleep(400);
    bot.chat(`//pos2 ${X1},${Y1},${Z1}`); await sleep(400);
    const setDone = waitChat(bot, /(blocks? have been changed|operation completed|affected)/i, 120000);
    bot.chat('//set air');
    const setMsg = await setDone;
    log(`  WE: ${(setMsg || '(no confirmation)').trim()}`);
    await sleep(7000);  // let the WAL-batched recorder drain to ClickHouse

    // sanity: how many break events did Spyglass record for this bot?
    const chq = encodeURIComponent(`SELECT count() FROM spyglass.event_records WHERE event='break' AND source_player_name='${BOT}'`);
    let recorded = '?';
    try { recorded = (await (await fetch(`http://localhost:8123/?query=${chq}`)).text()).trim(); } catch { }
    log(`  Spyglass recorded ${recorded} break events for ${BOT} (expect ~${VOL})`);

    log('Stage 2: /sg rollback p:' + BOT + ' t:1h -g …');
    let summary = null;
    const h = m => { if (/(reversals|No results|rolled back|applied)/i.test(m)) summary = m; };
    bot.on('messagestr', h);
    const t0 = Date.now();
    bot.chat(`/sg rollback p:${BOT} t:1h -g`);
    while (summary == null && Date.now() - t0 < 120000) await sleep(200);
    bot.removeListener('messagestr', h);
    const rbMs = Date.now() - t0;
    log(`  rollback line: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(none/timeout)'}  (${rbMs}ms)`);
    await sleep(4000);  // let post-main resend/finish settle

    log('Stage 3: independent COUNT (RCON /fill replace)…');
    const perChunk = await stonePerChunk();              // stone->glass, per chunk
    const stone = perChunk.reduce((a, r) => a + r.stone, 0);
    const air = await fillCube('bedrock', 'air');        // air->bedrock
    const corrupted = VOL - stone - air;

    log('\n──────── PER-CHUNK STONE RESTORED (of 4096 each) ────────');
    for (let cx = CX0; cx <= CX1; cx++) {
        const row = perChunk.filter(r => r.cx === cx).map(r => String(r.stone).padStart(5)).join(' ');
        log(`  cx=${cx}:  ${row}`);
    }
    log('\n──────── RESULT ────────');
    log(`  volume:           ${VOL}`);
    log(`  stone restored:   ${stone}   (${(100 * stone / VOL).toFixed(1)}%)`);
    log(`  air remaining:    ${air}   (${(100 * air / VOL).toFixed(1)}%)  <- NOT restored`);
    log(`  corrupted/other:  ${corrupted}   (${(100 * corrupted / VOL).toFixed(1)}%)  <- wrong block`);
    log(`  rollback claimed: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(none)'}`);
    const pass = stone === VOL && air === 0 && corrupted === 0;
    log(`\n  VERDICT: ${pass ? 'PASS — every block restored.' : 'FAIL — rollback did not fully restore the cube.'}`);

    bot.quit();
    await sleep(1000);
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y1} ${Z1} air`);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(pass ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
