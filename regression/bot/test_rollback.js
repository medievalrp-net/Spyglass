// End-to-end rollback regression test.
//
// Phases (per case):
//   1. Connect bot, get opped via RCON, teleport to a known void coord
//   2. //pos1 + //pos2 to define a cuboid of size N
//   3. //set stone — fills the cuboid (records N place events)
//   4. //set air — clears it (records N break events; this is the "burst")
//   5. /spyglass rollback p:<bot> t:5m — should restore the stone
//   6. Sample the cuboid and confirm every block is stone again
//
// Reports per-case pass/fail with timing + drift counts.

import mineflayer from 'mineflayer';
import net from 'net';
import fs from 'fs';
import path from 'path';
import vec3pkg from 'vec3';
const Vec3 = vec3pkg.Vec3 || vec3pkg.default || vec3pkg;

const SERVER_LOG = (process.env.RP_SERVER || new URL('../../../RP_Server', import.meta.url).pathname) + '/logs/latest.log';

// Read the server log and check whether ChunkDirectWriter / ChunkResender
// fell back to Bukkit. If yes, the rollback path is NOT exercising NMS
// and the visual/sand-fall fixes won't be active. We treat this as a
// hard test failure even if state correctness still passes.
function checkFallbackWarnings(sinceLine) {
    const text = fs.readFileSync(SERVER_LOG, 'utf8');
    const after = sinceLine ? text.slice(text.indexOf(sinceLine) + sinceLine.length) : text;
    const lines = after.split('\n');
    const warnings = lines.filter(l =>
        l.includes('ChunkDirectWriter unavailable') ||
        l.includes('ChunkDirectWriter failed') ||
        l.includes('ChunkResender unavailable') ||
        l.includes('ChunkResender failed'));
    return warnings;
}

function getLogTailMarker() {
    try {
        const text = fs.readFileSync(SERVER_LOG, 'utf8');
        return text.slice(-200);
    } catch (e) {
        return null;
    }
}

const HOST = '127.0.0.1';
const PORT = 25566;
const RCON_PORT = 25576;
const RCON_PASS = 'test123';
const BOT_NAME = 'rolltest';
const CH_URL = 'http://localhost:8123/';

// --- minimal RCON client (no extra deps) -----------------------------------

function rcon(cmd) {
    return new Promise((resolve, reject) => {
        const s = net.createConnection(RCON_PORT, HOST);
        let stage = 0;
        const requestId = 0x1337;
        const buffers = [];
        s.on('error', reject);
        s.on('connect', () => {
            // auth packet: type 3
            s.write(packet(requestId, 3, RCON_PASS));
        });
        s.on('data', chunk => {
            buffers.push(chunk);
            const all = Buffer.concat(buffers);
            // try to parse a complete response
            if (all.length < 4) return;
            const len = all.readInt32LE(0);
            if (all.length < len + 4) return;
            const id = all.readInt32LE(4);
            const type = all.readInt32LE(8);
            const body = all.slice(12, 12 + len - 10).toString('utf8');
            if (stage === 0) {
                if (id === -1) return reject(new Error('RCON auth failed'));
                stage = 1;
                buffers.length = 0;
                s.write(packet(requestId, 2, cmd));
            } else {
                s.end();
                resolve(body);
            }
        });
    });
}
function packet(id, type, body) {
    const bodyBuf = Buffer.from(body, 'utf8');
    const len = 4 + 4 + bodyBuf.length + 2;
    const out = Buffer.alloc(4 + len);
    out.writeInt32LE(len, 0);
    out.writeInt32LE(id, 4);
    out.writeInt32LE(type, 8);
    bodyBuf.copy(out, 12);
    out.writeInt16LE(0, 12 + bodyBuf.length);
    return out;
}

// --- helpers ---------------------------------------------------------------

async function chQuery(sql) {
    const r = await fetch(CH_URL, { method: 'POST', body: sql });
    return (await r.text()).trim();
}

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function logStep(...args) { console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...args); }

// --- test driver -----------------------------------------------------------

async function runCase(bot, { size, baseY }) {
    const SIDE = Math.ceil(Math.cbrt(size));
    const x0 = 1000, y0 = baseY, z0 = 1000;
    const x1 = x0 + SIDE - 1, y1 = y0 + SIDE - 1, z1 = z0 + SIDE - 1;
    const expected = SIDE * SIDE * SIDE;

    logStep(`--- case size=${size} (cube ${SIDE}^3 = ${expected} blocks, y=${y0}-${y1}) ---`);

    // Force-load FIRST so the destination chunks exist before the
    // teleport — otherwise the tp can silently fail and the bot stays
    // at spawn (observed: server-side getLocation() reported chunk(0,0)
    // even though the cube was at chunk(62,62), making the rollback
    // chunk-resend filter exclude the bot).
    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await sleep(800);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);
    // Verify bot actually moved server-side; if not, push it via
    // mineflayer's own movement.
    const tpProbe = await rcon(`data get entity ${BOT_NAME} Pos`);
    logStep(`  bot pos after tp: ${tpProbe.slice(0, 140)}`);

    // Use /fill (RCON, console origin) to lay stone. This does NOT fire
    // WorldEdit / BlockPlaceEvent listeners on Spyglass, so the rollback
    // window stays clean — only the bot's //set air burst is in scope.
    // Mojang's /fill is capped at 32768 blocks per call so we segment.
    const fillT0 = Date.now();
    const FILL_CAP = 32768;
    const cellsPerSlab = Math.floor(FILL_CAP / (SIDE * SIDE));
    let yCursor = y0;
    while (yCursor <= y1) {
        const yEnd = Math.min(y1, yCursor + Math.max(1, cellsPerSlab) - 1);
        const fr = await rcon(`fill ${x0} ${yCursor} ${z0} ${x1} ${yEnd} ${z1} stone`);
        logStep(`  fill slab y=${yCursor}-${yEnd} -> ${fr.replace(/\n/g, ' | ')}`);
        // also probe a single cell to see actual response format
        const sr = await rcon(`data get block ${x0} ${yCursor} ${z0} {}`);
        logStep(`  probe (${x0},${yCursor},${z0}) -> ${sr.slice(0, 120).replace(/\n/g, ' | ')}`);
        yCursor = yEnd + 1;
    }
    await sleep(500);
    const fillElapsed = Date.now() - fillT0;
    logStep(`fill (console /fill, no Spyglass events): ${fillElapsed}ms`);

    // probe via /execute if block — returns "Test passed" / "Test failed"
    async function probeCube(label) {
        let stone = 0, air = 0, other = 0, unloaded = 0;
        for (const [dx, dy, dz] of [[0,0,0],[5,5,5],[9,9,9],[3,7,2],[8,2,6]]) {
            const X = x0 + dx, Y = y0 + dy, Z = z0 + dz;
            const sr = await rcon(`execute if block ${X} ${Y} ${Z} minecraft:stone`);
            if (sr.toLowerCase().includes('not loaded')) { unloaded++; continue; }
            if (sr.toLowerCase().includes('passed')) { stone++; continue; }
            const ar = await rcon(`execute if block ${X} ${Y} ${Z} minecraft:air`);
            if (ar.toLowerCase().includes('passed')) { air++; continue; }
            other++;
        }
        logStep(`  probe(${label}): stone=${stone} air=${air} other=${other} unloaded=${unloaded}`);
        return { stone, air, other, unloaded };
    }
    const pre = await probeCube('post-fill');
    if (pre.stone < 4) {
        return { size, expected, ok: false, error: `fill did not lay stone — ${JSON.stringify(pre)}` };
    }

    // give CH a moment to settle before we mark the rollback baseline
    await sleep(2000);
    await probeCube('pre-burst-after-2s');
    const before = await chQuery(`SELECT count() FROM spyglass.event_records`);

    // bot WE commands: explicit cuboid mode + pos1/pos2 + //replace stone air.
    // //replace targets only the stone we /fill'd (precise scope) — bypassing
    // weirdness with //set air sometimes ranging beyond the selection.
    bot.chat(`//sel cuboid`);
    await sleep(200);
    bot.chat(`//pos1 ${x0},${y0},${z0}`);
    await sleep(200);
    bot.chat(`//pos2 ${x1},${y1},${z1}`);
    await sleep(200);
    bot.chat(`//size`);
    await sleep(400);

    // Wait for FAWE/WE //replace to actually finish (signaled via the
    // "blocks have been replaced" chat line). Then start the rollback
    // timer — measures real apply time, not script padding.
    const waitForChat = (re, timeout) => new Promise(resolve => {
        const handler = m => { if (re.test(m)) { bot.removeListener('messagestr', handler); resolve(true); } };
        bot.on('messagestr', handler);
        setTimeout(() => { bot.removeListener('messagestr', handler); resolve(false); }, timeout);
    });
    const burstT0 = Date.now();
    const burstChatPromise = waitForChat(/blocks have been replaced/i, Math.max(30_000, expected / 50));
    bot.chat(`//replace stone air`);
    await burstChatPromise;
    const burstElapsed = Date.now() - burstT0;
    // brief settle so FAWE's worker flushes its writes before we sample
    await sleep(1500);
    await probeCube('post-burst');

    // immediately roll back. Wait for the «Spyglass» summary chat line
    // (or skip-reason variant) to know rollback is actually done.
    const rbT0 = Date.now();
    const rbDonePromise = waitForChat(/(reversals|No results)/i, Math.min(900_000, Math.max(60_000, expected)));
    bot.chat(`/spyglass rollback a:break p:${BOT_NAME} t:5m -g`);
    logStep(`burst: ${burstElapsed}ms, rollback issued`);
    const rolledBack = await rbDonePromise;
    const rbElapsed = Date.now() - rbT0;
    if (!rolledBack) {
        logStep(`rollback did not signal completion within budget`);
    } else {
        logStep(`rollback signaled complete in ${rbElapsed}ms`);
    }
    // post-rollback settle so the last per-tick apply batch lands
    await sleep(2000);

    // bulk-verify post-rollback by sampling a deterministic stride of cells.
    // Server-side check (via /execute) confirms the world state is correct.
    // Client-side check (via bot.world from received packets) confirms the
    // chunk packets actually reached the client — this is the difference
    // between "blocks rolled back invisibly" (server right, client stale)
    // and "blocks rolled back visibly" (matching server + client).
    let serverStone = 0, serverAir = 0, serverOther = 0;
    let clientStone = 0, clientAir = 0, clientOther = 0;
    const samples = Math.min(64, expected);
    const stride = Math.max(1, Math.floor(expected / samples));
    let scanned = 0;
    for (let dx = 0; dx < SIDE && scanned < samples; dx++) {
        for (let dy = 0; dy < SIDE && scanned < samples; dy++) {
            for (let dz = 0; dz < SIDE && scanned < samples; dz++) {
                if ((dx * SIDE * SIDE + dy * SIDE + dz) % stride !== 0) continue;
                const X = x0 + dx, Y = y0 + dy, Z = z0 + dz;
                // server-side
                const sr = await rcon(`execute if block ${X} ${Y} ${Z} minecraft:stone`);
                if (sr.toLowerCase().includes('passed')) serverStone++;
                else {
                    const ar = await rcon(`execute if block ${X} ${Y} ${Z} minecraft:air`);
                    if (ar.toLowerCase().includes('passed')) serverAir++;
                    else serverOther++;
                }
                // client-side (mineflayer's world model — only updated by packets the bot received)
                const cb = bot.blockAt(new Vec3(X, Y, Z));
                const cn = cb ? cb.name : 'unknown';
                if (cn === 'stone') clientStone++;
                else if (cn === 'air' || cn === 'cave_air' || cn === 'void_air') clientAir++;
                else clientOther++;
                scanned++;
            }
        }
    }

    const breakCount = await chQuery(
        `SELECT count() FROM spyglass.event_records WHERE event = 'break' AND source_player_name = '${BOT_NAME}' AND occurred > now() - INTERVAL 10 MINUTE`);
    const restoreCount = await chQuery(
        `SELECT count() FROM spyglass.event_records WHERE event = 'rolled-place' AND occurred > now() - INTERVAL 10 MINUTE`);

    const serverOk = serverStone === scanned;
    const clientOk = clientStone === scanned;
    const ok = serverOk && clientOk;
    logStep(`server scan: stone=${serverStone} air=${serverAir} other=${serverOther} of ${scanned} — ${serverOk ? 'PASS' : 'FAIL'}`);
    logStep(`client scan: stone=${clientStone} air=${clientAir} other=${clientOther} of ${scanned} — ${clientOk ? 'PASS' : 'FAIL'}`);
    logStep(`CH rows: break=${breakCount} (expected ~${expected}), rolled-place=${restoreCount}`);
    return { size, expected, scanned, serverStone, serverAir, serverOther, clientStone, clientAir, clientOther, ok, fillElapsed, burstElapsed, rbElapsed, breakCount, restoreCount };
}

async function main() {
    logStep('connecting bot...');
    const bot = mineflayer.createBot({
        host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4',
    });
    await new Promise((resolve, reject) => {
        bot.once('spawn', resolve);
        bot.once('error', reject);
        bot.once('end', () => reject(new Error('bot disconnected before spawn')));
    });
    bot.on('messagestr', m => {
        // pipe useful WE/Spyglass chat lines to the test log
        if (/(position|reversal|selection|skip|querying|spyglass|blocks|skipped|reason)/i.test(m)) {
            logStep('  chat>', m.replace(/\s+/g, ' ').slice(0, 200));
        }
    });
    logStep('spawned. opping bot via RCON...');
    await rcon(`op ${BOT_NAME}`);
    await rcon(`gamemode creative ${BOT_NAME}`);
    await sleep(500);

    // Mark current log tail so we only check warnings emitted from now on.
    const logMarker = getLogTailMarker();

    const sizes = parseSizes();
    const results = [];
    for (const size of sizes) {
        try {
            results.push(await runCase(bot, { size, baseY: 80 + sizes.indexOf(size) * 30 }));
        } catch (e) {
            logStep(`case size=${size} threw:`, e.message);
            results.push({ size, ok: false, error: e.message });
        }
        await sleep(2000);
    }

    // Sand-column undo regression: this is the user's bug.
    // Build a 16-tall sand column on a stone support, break it via
    // //replace, rollback (which restores the column), then /sg undo
    // — the undo writes air back into the column. The test verifies
    // that the WRITES alone don't cause sand to fall: every cell
    // must be air after a 1-second settle (no gravity-cascaded sand
    // landing in the column).
    try {
        results.push(await runSandUndoCase(bot, { baseY: 80 + sizes.length * 30 }));
    } catch (e) {
        logStep('sand-undo case threw:', e.message);
        results.push({ id: 'sand-undo', ok: false, error: e.message });
    }

    bot.quit();
    console.log('\n=== summary ===');
    for (const r of results) console.log(JSON.stringify(r));

    // Hard fail if the rollback path took the Bukkit fallback. State
    // correctness can pass with the fallback (Bukkit setBlockData
    // actually writes blocks), but the user-facing visual + sand-fall
    // bugs only get fixed if NMS direct writes actually executed.
    const fallbackWarnings = checkFallbackWarnings(logMarker);
    if (fallbackWarnings.length > 0) {
        console.log('\n=== FALLBACK DETECTED ===');
        for (const w of fallbackWarnings.slice(0, 5)) console.log(w);
        console.log(`(... ${fallbackWarnings.length} total fallback warnings)`);
        console.log('Rollback ran on Bukkit fallback, NOT NMS. Visual + sand-fall bugs are NOT fixed.');
        process.exit(2);
    }

    const failures = results.filter(r => !r.ok).length;
    process.exit(failures ? 1 : 0);
}

async function runSandUndoCase(bot, { baseY }) {
    const COLUMN_H = 16;
    const x0 = 1100, z0 = 1100;
    const yStone = baseY;            // support
    const ySandLo = baseY + 1;
    const ySandHi = baseY + COLUMN_H;
    logStep(`--- sand-undo regression at (${x0}, ${yStone}+, ${z0}), column height=${COLUMN_H} ---`);

    await rcon(`forceload add ${x0} ${z0} ${x0} ${z0}`);
    await sleep(500);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${ySandHi + 5} ${z0 + 0.5}`);
    await sleep(2000);

    // Lay one stone at yStone, sand from ySandLo..ySandHi
    await rcon(`fill ${x0} ${yStone} ${z0} ${x0} ${yStone} ${z0} stone`);
    await rcon(`fill ${x0} ${ySandLo} ${z0} ${x0} ${ySandHi} ${z0} sand`);
    await sleep(500);
    // Probe to confirm the column is laid
    const probe1 = await rcon(`execute if block ${x0} ${ySandLo} ${z0} minecraft:sand`);
    logStep(`  laid: ${probe1.replace(/\n/g, ' | ')}`);

    // Bot breaks the column via //replace
    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x0},${ySandLo},${z0}`); await sleep(150);
    bot.chat(`//pos2 ${x0},${ySandHi},${z0}`); await sleep(150);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 10000);
    bot.chat(`//replace sand air`);
    await replaceDone;
    await sleep(800);

    // Rollback restores the sand column
    const rbDone = waitForChat(bot, /(reversals|No results)/i, 60000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:5m -g`);
    await rbDone;
    await sleep(800);

    // Undo the rollback — every cell goes back to air. KEY ASSERTION:
    // after a settle, every column cell must STILL be air. If gravity
    // fires during the undo, sand from above would re-fall and land
    // in the column.
    const undoDone = waitForChat(bot, /(reversed|undone|reverted|No results|reversals)/i, 30000);
    bot.chat(`/spyglass undo`);
    await undoDone;
    await sleep(1500);  // gravity settle window

    let air = 0, sand = 0, other = 0;
    for (let y = ySandLo; y <= ySandHi; y++) {
        const sr = await rcon(`execute if block ${x0} ${y} ${z0} minecraft:air`);
        if (sr.toLowerCase().includes('passed')) { air++; continue; }
        const sd = await rcon(`execute if block ${x0} ${y} ${z0} minecraft:sand`);
        if (sd.toLowerCase().includes('passed')) { sand++; continue; }
        other++;
    }
    // Also check below the support: if sand spilled past the support, it
    // would have landed at yStone-N. Sample a few cells below to detect that.
    let belowSand = 0;
    for (let y = yStone - 1; y >= yStone - 5; y--) {
        const sd = await rcon(`execute if block ${x0} ${y} ${z0} minecraft:sand`);
        if (sd.toLowerCase().includes('passed')) belowSand++;
    }

    const ok = sand === 0 && belowSand === 0 && air === COLUMN_H;
    logStep(`column scan: air=${air} sand=${sand} other=${other} of ${COLUMN_H} — belowSand=${belowSand} — ${ok ? 'PASS' : 'FAIL'}`);
    return { id: 'sand-undo', columnHeight: COLUMN_H, air, sand, other, belowSand, ok };
}

function waitForChat(bot, re, timeout) {
    return new Promise(resolve => {
        const handler = m => { if (re.test(m)) { bot.removeListener('messagestr', handler); resolve(true); } };
        bot.on('messagestr', handler);
        setTimeout(() => { bot.removeListener('messagestr', handler); resolve(false); }, timeout);
    });
}

function parseSizes() {
    const arg = process.argv[2];
    if (!arg) return [1000, 5000, 20000];
    return arg.split(',').map(s => parseInt(s, 10));
}

main().catch(e => { console.error(e); process.exit(2); });
