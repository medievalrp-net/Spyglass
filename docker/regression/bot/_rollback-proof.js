// Explicit before/after block count around a //set-air -> /sg rollback.
// Every count is vanilla `/fill ... replace` over RCON (server-side ground
// truth), never Spyglass's own numbers. Usage: node _rollback-proof.js [SIDE]
//
// Stages, all counted independently:
//   0. /fill stone baseline (unlogged)                 -> expect SIDE^3 stone
//   1. bot //set air (LOGGED), Y-slabbed for keepalive
//   1.5 count BEFORE rollback                           -> expect 0 stone, all air
//   2. /sg rollback p:<bot>
//   3. count AFTER rollback                             -> expect all stone, 0 air
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

const SIDE = parseInt(process.argv[2] || '64', 10);
// Fresh region away from earlier tests; Y kept so per-chunk*fullY <= 32768.
const X0 = 24000, Y0 = 72, Z0 = 24000;
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

// Chunk-tiled fill: one /fill per 16x16 column over full Y (<= 32768 for
// Y span <= 128), summed. Works at any SIDE (the 8-high slab in the older
// script overflows the cap once SIDE > 64).
async function fill(block, filter) {
    let total = 0;
    for (let cx = CX0; cx <= CX1; cx++) {
        for (let cz = CZ0; cz <= CZ1; cz++) {
            const bx0 = Math.max(X0, cx << 4), bx1 = Math.min(X1, (cx << 4) + 15);
            const bz0 = Math.max(Z0, cz << 4), bz1 = Math.min(Z1, (cz << 4) + 15);
            const r = await rcon(`fill ${bx0} ${Y0} ${bz0} ${bx1} ${Y1} ${bz1} ${block}${filter ? ` replace ${filter}` : ''}`);
            total += countOf(r);
        }
    }
    return total;
}
// Non-destructive count of `target`: count via target->glass, then restore
// glass->target. Net world state unchanged, so the rollback still has real
// work to do afterward.
async function countKeep(target) {
    const n = await fill('glass', target);
    await fill(target, 'glass');
    return n;
}
// Fill an arbitrary box with stone, chunk-tiled (<=32768/column for Y<=128).
async function fillBox(ax0, ay0, az0, ax1, ay1, az1) {
    for (let cx = ax0 >> 4; cx <= ax1 >> 4; cx++) {
        for (let cz = az0 >> 4; cz <= az1 >> 4; cz++) {
            const bx0 = Math.max(ax0, cx << 4), bx1 = Math.min(ax1, (cx << 4) + 15);
            const bz0 = Math.max(az0, cz << 4), bz1 = Math.min(az1, (cz << 4) + 15);
            await rcon(`fill ${bx0} ${ay0} ${bz0} ${bx1} ${ay1} ${bz1} stone`);
        }
    }
}
// 1-block stone shell on all six faces around the cube.
async function sealShell() {
    await fillBox(X0 - 1, Y0 - 1, Z0 - 1, X1 + 1, Y0 - 1, Z1 + 1); // bottom
    await fillBox(X0 - 1, Y1 + 1, Z0 - 1, X1 + 1, Y1 + 1, Z1 + 1); // top
    await fillBox(X0 - 1, Y0, Z0 - 1, X1 + 1, Y1, Z0 - 1);         // north
    await fillBox(X0 - 1, Y0, Z1 + 1, X1 + 1, Y1, Z1 + 1);         // south
    await fillBox(X0 - 1, Y0, Z0, X0 - 1, Y1, Z1);                 // west
    await fillBox(X1 + 1, Y0, Z0, X1 + 1, Y1, Z1);                 // east
}
function waitChat(bot, re, timeout) {
    return new Promise(resolve => {
        const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); resolve(m); } };
        bot.on('messagestr', h);
        setTimeout(() => { bot.removeListener('messagestr', h); resolve(null); }, timeout);
    });
}

const BOT = 'rbp' + Date.now().toString(36).slice(-4);

(async () => {
    log(`Region ${SIDE}^3 = ${VOL.toLocaleString()} blocks @ (${X0},${Y0},${Z0})..(${X1},${Y1},${Z1})`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(1500);

    log('Stage 0: /fill stone baseline (RCON, unlogged)…');
    // Open cube (no seal): generated-terrain fluid WILL flow into the air
    // pocket between //set and the rollback. Force-overwrite (#69) must
    // restore stone over that drift -> a clean 2M/2M anyway.
    await fill('air');
    const filled = await fill('stone');
    log(`  BEFORE EDIT  -> stone = ${filled.toLocaleString()} / air = ${(VOL - filled).toLocaleString()}   (expect ${VOL.toLocaleString()} stone)`);

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
    log(`  WorldEdit //set air affected ${weAffected.toLocaleString()} blocks total`);
    await sleep(8000); // let the recorder drain to ClickHouse

    const chq = encodeURIComponent(`SELECT count() FROM spyglass.event_records WHERE event='break' AND source_player_name='${BOT}'`);
    let recorded = '?';
    try { recorded = (await (await fetch(`http://localhost:8123/?query=${chq}`)).text()).trim(); } catch { }
    log(`  Spyglass recorded ${Number(recorded).toLocaleString()} break events for ${BOT}`);

    log('Stage 1.5: COUNT after //set air (independent /fill replace)…');
    const stoneMid = await fill('glass', 'stone'); // no stone left => 0, non-destructive
    const airMid = await countKeep('air');         // count air, restore to air
    log(`  after //set air -> stone = ${stoneMid.toLocaleString()} / air = ${airMid.toLocaleString()}`);

    // Stage 1.6: DETERMINISTIC drift — overwrite every cell with dirt
    // (UNLOGGED) so the live state no longer matches the recorded 'air'.
    // Old skip-if-changed would skip all of these; force-overwrite (#69)
    // must restore stone over them. Dirt is solid (no flow) so the count
    // is exact, and it stands in for any post-edit change (water/lava/etc.).
    log('Stage 1.6: overwrite the cube with DIRT (unlogged drift)…');
    await fill('dirt');
    const dirtMid = await fill('glass', 'dirt');   // count dirt, leaves glass...
    await fill('dirt', 'glass');                    // ...restore glass->dirt
    log(`  before rollback -> dirt = ${dirtMid.toLocaleString()} (cube no longer matches recorded air)`);

    log('Stage 2: /sg rollback p:' + BOT + ' t:1h -g …');
    let summary = null;
    const h = m => { if (/(reversals|No results|rolled back|applied)/i.test(m)) summary = m; };
    bot.on('messagestr', h);
    const t0 = Date.now();
    bot.chat(`/sg rollback p:${BOT} t:1h -g`);
    while (summary == null && Date.now() - t0 < 180000) await sleep(200);
    bot.removeListener('messagestr', h);
    log(`  rollback line: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(none/timeout)'}  (${Date.now() - t0}ms)`);
    await sleep(5000);

    log('Stage 3: COUNT AFTER ROLLBACK (independent /fill replace)…');
    const stoneAfter = await fill('glass', 'stone');   // stone -> glass
    const airAfter = await fill('bedrock', 'air');      // air -> bedrock
    const corrupted = VOL - stoneAfter - airAfter;

    log('\n──────── BEFORE / AFTER (server-side counts) ────────');
    log(`  volume:                 ${VOL.toLocaleString()}`);
    log(`  before edit:            stone ${filled.toLocaleString()}  / air ${(VOL - filled).toLocaleString()}`);
    log(`  after //set air:        stone ${stoneMid.toLocaleString()}  / air ${airMid.toLocaleString()}`);
    log(`  drift injected:         dirt  ${dirtMid.toLocaleString()}  (live state changed away from recorded air)`);
    log(`  after /sg rollback:     stone ${stoneAfter.toLocaleString()}  / air ${airAfter.toLocaleString()}  / corrupted(dirt) ${corrupted.toLocaleString()}`);
    log(`  rollback claimed:       ${summary ? summary.replace(/\s+/g, ' ').trim() : '(none)'}`);
    // Force-overwrite contract (#69): the rollback restores every recorded
    // block over the dirt drift. PASS = full stone, no air, no leftover dirt.
    // (Old skip-if-changed would leave all ${VOL} as dirt: stone 0.)
    const pass = filled === VOL && dirtMid === VOL
        && stoneAfter === VOL && airAfter === 0 && corrupted === 0;
    log(`\n  VERDICT: ${pass
        ? `PASS — cube was changed to ${VOL.toLocaleString()} dirt; force-overwrite restored all ${VOL.toLocaleString()} back to stone.`
        : 'FAIL — rollback did not force-overwrite the drift.'}`);

    // Diagnostic: if anything is left over, the restored stone is now glass
    // and air is bedrock; probe what the leftover blocks actually are.
    if (!pass && corrupted > 0) {
        log('\n──────── PROBE: what are the leftover blocks? ────────');
        for (const t of ['stone', 'glass', 'bedrock', 'dirt', 'cobblestone', 'water', 'lava', 'gravel', 'sand', 'cave_air', 'void_air']) {
            const n = await fill('diamond_block', t);
            if (n > 0) log(`  ${t}: ${n.toLocaleString()}`);
            if (n > 0) await fill(t, 'diamond_block'); // restore the probe
        }
    }

    bot.quit();
    await sleep(1000);
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y1} ${Z1} air`);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(pass ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
