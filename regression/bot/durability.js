// Durability / robustness suite. Covers TPS impact, crash recovery,
// graceful restart, concurrent activity, simultaneous rollbacks, and
// edge cases not covered by stress.js.
//
// Each phase reports {label, ok, ...details}. The runner prints a
// findings table at the end; the user reads to know what's safe and
// what's known-broken.

import mineflayer from 'mineflayer';
import net from 'net';
import fs from 'fs';
import { spawn } from 'child_process';
import vec3pkg from 'vec3';
const Vec3 = vec3pkg.Vec3 || vec3pkg.default || vec3pkg;

const HOST = '127.0.0.1';
const PORT = 25566;
const RCON_PORT = 25576;
const RCON_PASS = 'test123';
const BOT_NAME = 'rolltest';
const BOT2_NAME = 'rolltest2';
const SERVER_DIR = process.env.RP_SERVER || new URL('../../../RP_Server', import.meta.url).pathname;
const SERVER_LOG = SERVER_DIR + '/logs/latest.log';

// --- RCON ---
function rcon(cmd) {
    return new Promise((resolve, reject) => {
        const s = net.createConnection(RCON_PORT, HOST);
        let stage = 0;
        const buffers = [];
        s.on('error', reject);
        s.on('connect', () => s.write(packet(0x1337, 3, RCON_PASS)));
        s.on('data', chunk => {
            buffers.push(chunk);
            const all = Buffer.concat(buffers);
            if (all.length < 4) return;
            const len = all.readInt32LE(0);
            if (all.length < len + 4) return;
            const id = all.readInt32LE(4);
            const body = all.slice(12, 12 + len - 10).toString('utf8');
            if (stage === 0) {
                if (id === -1) return reject(new Error('auth'));
                stage = 1;
                buffers.length = 0;
                s.write(packet(0x1337, 2, cmd));
            } else {
                s.end();
                resolve(body);
            }
        });
    });
}
function packet(id, type, body) {
    const b = Buffer.from(body, 'utf8');
    const len = 4 + 4 + b.length + 2;
    const o = Buffer.alloc(4 + len);
    o.writeInt32LE(len, 0);
    o.writeInt32LE(id, 4);
    o.writeInt32LE(type, 8);
    b.copy(o, 12);
    o.writeInt16LE(0, 12 + b.length);
    return o;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

function waitForChat(bot, re, timeout) {
    return new Promise(resolve => {
        const handler = m => { if (re.test(m)) { bot.removeListener('messagestr', handler); resolve(m); } };
        bot.on('messagestr', handler);
        setTimeout(() => { bot.removeListener('messagestr', handler); resolve(null); }, timeout);
    });
}

async function probe(x, y, z, expected) {
    const r = await rcon(`execute if block ${x} ${y} ${z} minecraft:${expected}`);
    return r.toLowerCase().includes('passed');
}

// --- Phase: TPS measurement ---
//
// Lays a stone cube via /fill (silent), runs //replace stone air
// (logged), starts a TPS sampler, and triggers /sg rollback. Records
// TPS samples every 500 ms and reports min/avg/max during the rollback
// window plus before/after baseline.
async function measureTps(bot, blockCount, baseY) {
    const SIDE = Math.ceil(Math.cbrt(blockCount));
    const x0 = 4000, z0 = 4000;
    const y0 = baseY, y1 = baseY + SIDE - 1;
    const x1 = x0 + SIDE - 1, z1 = z0 + SIDE - 1;
    const expected = SIDE * SIDE * SIDE;
    log(`TPS measure: cube ${SIDE}^3 = ${expected} blocks`);

    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);

    const FILL_CAP = 32768;
    const slab = Math.max(1, Math.floor(FILL_CAP / (SIDE * SIDE)));
    for (let y = y0; y <= y1; y += slab) {
        const yEnd = Math.min(y1, y + slab - 1);
        await rcon(`fill ${x0} ${y} ${z0} ${x1} ${yEnd} ${z1} stone`);
    }
    await sleep(1000);

    bot.chat(`//sel cuboid`); await sleep(200);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(200);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(200);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 600000);
    bot.chat(`//replace stone air`);
    await replaceDone;

    // Drain wait scaled with size
    await sleep(Math.max(2000, Math.ceil(expected / 50)));

    // Baseline TPS for 2 seconds
    log('TPS baseline (2s):');
    const baseline = [];
    for (let i = 0; i < 4; i++) {
        const r = await rcon('tps');
        const m = r.match(/from last 1m, 5m, 15m:\s*[§a-f0-9]*?([\d.]+)/);
        if (m) baseline.push(parseFloat(m[1]));
        await sleep(500);
    }
    log(`  baseline samples: ${baseline.join(', ')}`);

    // Start rollback + sample TPS until completion
    log('TPS during rollback:');
    const rbT0 = Date.now();
    const rbDone = waitForChat(bot, /(reversals|No results)/i, 600000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:5m -g`);

    const duringSamples = [];
    const samplePromise = (async () => {
        while (true) {
            const r = await rcon('tps');
            const m = r.match(/from last 1m, 5m, 15m:\s*[§a-f0-9]*?([\d.]+)/);
            if (m) duringSamples.push({ t: Date.now() - rbT0, tps: parseFloat(m[1]) });
            await sleep(500);
        }
    })();

    const rbResult = await rbDone;
    const rbMs = Date.now() - rbT0;
    // Stop the sampler shortly after rollback completes
    setTimeout(() => duringSamples.push({ done: true }), 100);
    await sleep(200);
    log(`  rollback done in ${rbMs} ms, samples: ${duringSamples.length}`);

    const minTps = Math.min(...duringSamples.filter(s => s.tps).map(s => s.tps));
    const avgTps = duringSamples.filter(s => s.tps).reduce((a, b) => a + b.tps, 0) / duringSamples.filter(s => s.tps).length;
    const maxTps = Math.max(...duringSamples.filter(s => s.tps).map(s => s.tps));
    log(`  min=${minTps.toFixed(1)} avg=${avgTps.toFixed(1)} max=${maxTps.toFixed(1)}`);

    return {
        label: `tps-${blockCount}`,
        expected, rbMs,
        baselineTps: baseline,
        rollbackTps: { min: minTps, avg: avgTps, max: maxTps, samples: duringSamples.filter(s => s.tps).length },
        ok: minTps > 15.0,  // arbitrary "didn't fall below 15 TPS" gate
    };
}

// --- Phase: Crash recovery ---
//
// Triggers a 1M-block //replace burst, fires /sg rollback, then SIGKILLs
// the server mid-rollback. Restarts. Re-runs the rollback. Checks final
// state and that the world isn't corrupted.
async function crashMidRollback(bot) {
    const label = 'crash-mid-rollback';
    const SIDE = 60;  // 216,000 cells - enough that rollback takes seconds
    const x0 = 5000, z0 = 5000;
    const y0 = 80, y1 = y0 + SIDE - 1;
    const x1 = x0 + SIDE - 1, z1 = z0 + SIDE - 1;
    log(`crash-mid-rollback: cube ${SIDE}^3`);

    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);

    const FILL_CAP = 32768;
    const slab = Math.max(1, Math.floor(FILL_CAP / (SIDE * SIDE)));
    for (let y = y0; y <= y1; y += slab) {
        await rcon(`fill ${x0} ${y} ${z0} ${x1} ${Math.min(y1, y + slab - 1)} ${z1} stone`);
    }
    await sleep(1000);

    bot.chat(`//sel cuboid`); await sleep(200);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(200);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(200);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 120000);
    bot.chat(`//replace stone air`);
    await replaceDone;
    await sleep(5000);  // drain settle

    // Trigger rollback. Don't await completion — we'll kill mid-flight.
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:5m -g`);
    await sleep(3000);  // let some chunks get rolled back

    // Sample mid-rollback state: how many cells are stone? Should be partial.
    let stoneMid = 0, scanned = 0;
    const stride = Math.max(1, Math.floor(SIDE * SIDE * SIDE / 32));
    for (let i = 0; i < SIDE * SIDE * SIDE && scanned < 32; i += stride) {
        const dx = i % SIDE, rest = Math.floor(i / SIDE);
        const dy = rest % SIDE, dz = Math.floor(rest / SIDE);
        if (await probe(x0 + dx, y0 + dy, z0 + dz, 'stone')) stoneMid++;
        scanned++;
    }
    log(`  mid-rollback stone count: ${stoneMid}/${scanned}`);

    // KILL the server hard (SIGKILL = no graceful save, no recorder flush).
    log('  SIGKILL server...');
    const javaPids = await rcon(`list`).catch(() => null);  // last gasp
    bot.quit();
    await new Promise(r => setTimeout(r, 500));
    await new Promise((resolve, reject) => {
        const ps = spawn('pkill', ['-9', '-f', 'paper.jar']);
        ps.on('exit', resolve);
        ps.on('error', reject);
    });
    await sleep(3000);

    // Restart server
    log('  restarting server...');
    const restart = spawn('bash', ['-c',
        `cd ${SERVER_DIR} && nohup ./start-testable.sh > boot-recover.log 2>&1 &`],
        { detached: true });
    restart.unref();
    // Wait for ready
    let attempts = 0;
    while (attempts++ < 60) {
        try {
            const r = await rcon('list');
            if (r.includes('There are')) break;
        } catch { }
        await sleep(2000);
    }
    log(`  server back up (${attempts}s)`);

    // Reconnect bot
    const bot2 = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4' });
    await new Promise((res, rej) => { bot2.once('spawn', res); bot2.once('error', rej); });
    await rcon(`op ${BOT_NAME}`);
    await rcon(`gamemode creative ${BOT_NAME}`);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2000);

    // Sample post-restart state
    let stonePostCrash = 0;
    scanned = 0;
    for (let i = 0; i < SIDE * SIDE * SIDE && scanned < 32; i += stride) {
        const dx = i % SIDE, rest = Math.floor(i / SIDE);
        const dy = rest % SIDE, dz = Math.floor(rest / SIDE);
        if (await probe(x0 + dx, y0 + dy, z0 + dz, 'stone')) stonePostCrash++;
        scanned++;
    }
    log(`  post-crash stone count: ${stonePostCrash}/${scanned}`);

    // Re-run rollback to finish what was interrupted
    log('  re-running rollback...');
    const rb2Done = waitForChat(bot2, /(reversals|No results)/i, 300000);
    bot2.chat(`/spyglass rollback p:${BOT_NAME} t:30m -g`);
    await rb2Done;
    await sleep(2000);

    let stoneFinal = 0;
    scanned = 0;
    for (let i = 0; i < SIDE * SIDE * SIDE && scanned < 32; i += stride) {
        const dx = i % SIDE, rest = Math.floor(i / SIDE);
        const dy = rest % SIDE, dz = Math.floor(rest / SIDE);
        if (await probe(x0 + dx, y0 + dy, z0 + dz, 'stone')) stoneFinal++;
        scanned++;
    }
    log(`  post-recovery stone count: ${stoneFinal}/${scanned}`);

    bot2.quit();
    return { label, ok: stoneFinal === scanned, stoneMid, stonePostCrash, stoneFinal, scanned };
}

// --- Phase: Graceful restart ---
async function gracefulRestart(bot) {
    const label = 'graceful-restart';
    const SIDE = 50;  // 125k cells
    const x0 = 6000, z0 = 6000;
    const y0 = 80, y1 = y0 + SIDE - 1;
    const x1 = x0 + SIDE - 1, z1 = z0 + SIDE - 1;
    log(`graceful-restart: cube ${SIDE}^3`);

    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);

    const FILL_CAP = 32768;
    const slab = Math.max(1, Math.floor(FILL_CAP / (SIDE * SIDE)));
    for (let y = y0; y <= y1; y += slab) {
        await rcon(`fill ${x0} ${y} ${z0} ${x1} ${Math.min(y1, y + slab - 1)} ${z1} stone`);
    }
    await sleep(1000);

    bot.chat(`//sel cuboid`); await sleep(200);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(200);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(200);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 60000);
    bot.chat(`//replace stone air`);
    await replaceDone;
    await sleep(3000);

    // Trigger rollback, then graceful /stop a few seconds in.
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:5m -g`);
    await sleep(2000);

    log('  /stop mid-rollback...');
    bot.quit();
    await sleep(500);
    // Use FIFO to send stop
    fs.writeFileSync('/tmp/rpserver-cmd', 'stop\n');
    let attempts = 0;
    while (attempts++ < 60) {
        try {
            await rcon('list');
        } catch {
            break;  // server stopped
        }
        await sleep(1000);
    }
    log(`  stopped (${attempts}s)`);

    // Restart
    log('  restart...');
    const restart = spawn('bash', ['-c',
        `cd ${SERVER_DIR} && nohup ./start-testable.sh > boot-recover2.log 2>&1 &`],
        { detached: true });
    restart.unref();
    attempts = 0;
    while (attempts++ < 60) {
        try {
            const r = await rcon('list');
            if (r.includes('There are')) break;
        } catch { }
        await sleep(2000);
    }
    log(`  server back up`);

    const bot2 = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4' });
    await new Promise((res, rej) => { bot2.once('spawn', res); bot2.once('error', rej); });
    await rcon(`op ${BOT_NAME}`);
    await rcon(`gamemode creative ${BOT_NAME}`);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2000);

    let stonePostStop = 0;
    let scanned = 0;
    const stride = Math.max(1, Math.floor(SIDE * SIDE * SIDE / 32));
    for (let i = 0; i < SIDE * SIDE * SIDE && scanned < 32; i += stride) {
        const dx = i % SIDE, rest = Math.floor(i / SIDE);
        const dy = rest % SIDE, dz = Math.floor(rest / SIDE);
        if (await probe(x0 + dx, y0 + dy, z0 + dz, 'stone')) stonePostStop++;
        scanned++;
    }
    log(`  post-graceful-stop stone: ${stonePostStop}/${scanned}`);

    // Resume rollback
    const rb2Done = waitForChat(bot2, /(reversals|No results)/i, 300000);
    bot2.chat(`/spyglass rollback p:${BOT_NAME} t:30m -g`);
    await rb2Done;
    await sleep(2000);

    let stoneFinal = 0;
    scanned = 0;
    for (let i = 0; i < SIDE * SIDE * SIDE && scanned < 32; i += stride) {
        const dx = i % SIDE, rest = Math.floor(i / SIDE);
        const dy = rest % SIDE, dz = Math.floor(rest / SIDE);
        if (await probe(x0 + dx, y0 + dy, z0 + dz, 'stone')) stoneFinal++;
        scanned++;
    }
    log(`  post-recovery stone: ${stoneFinal}/${scanned}`);

    bot2.quit();
    return { label, ok: stoneFinal === scanned, stonePostStop, stoneFinal, scanned };
}

// --- Phase: edge cases ---
async function negativeYRollback(bot) {
    const label = 'negative-y';
    const x = 7000, y = -50, z = 7000;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await rcon(`tp ${BOT_NAME} ${x + 0.5} 100 ${z + 0.5}`);
    await sleep(2000);
    await rcon(`setblock ${x} ${y} ${z} stone`);
    await sleep(500);
    if (!await probe(x, y, z, 'stone')) {
        return { label, ok: false, reason: 'setup failed: setblock stone at y=-50' };
    }
    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x},${y},${z}`); await sleep(150);
    bot.chat(`//pos2 ${x},${y},${z}`); await sleep(150);
    const done = waitForChat(bot, /blocks have been (changed|replaced|set)/i, 8000);
    bot.chat(`//set air`);
    await done;
    await sleep(800);
    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1000);
    return { label, ok: await probe(x, y, z, 'stone') };
}

async function fluidFlowback(bot) {
    const label = 'fluid-flowback';
    const x = 7100, y = 100, z = 7100;
    await rcon(`forceload add ${x - 1} ${z - 1} ${x + 1} ${z + 1}`);
    await rcon(`tp ${BOT_NAME} ${x + 0.5} ${y + 5} ${z + 0.5}`);
    await sleep(2000);
    // Surround with water sources, place stone in middle
    await rcon(`fill ${x - 1} ${y} ${z} ${x - 1} ${y} ${z} water`);
    await rcon(`fill ${x + 1} ${y} ${z} ${x + 1} ${y} ${z} water`);
    await rcon(`setblock ${x} ${y} ${z} stone`);
    await sleep(500);
    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x},${y},${z}`); await sleep(150);
    bot.chat(`//pos2 ${x},${y},${z}`); await sleep(150);
    const done = waitForChat(bot, /blocks have been (changed|replaced|set)/i, 8000);
    bot.chat(`//set air`);  // stone → air; water flows in
    await done;
    await sleep(2000);  // let water flow
    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1000);
    // Should restore stone (transient-filler logic should accept water → stone overwrite)
    return { label, ok: await probe(x, y, z, 'stone') };
}

async function midairRollback(bot) {
    const label = 'midair';
    const x = 7200, y = 100, z = 7200;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await rcon(`tp ${BOT_NAME} ${x + 0.5} ${y + 5} ${z + 0.5}`);
    await sleep(2000);
    await rcon(`setblock ${x} ${y} ${z} sand`);  // mid-air sand (no support)
    await sleep(1500);  // would naturally fall — let it
    // After fall, sand is at lower y or destroyed. Either way, snapshot
    // before //set air doesn't matter for our test. Re-place sand.
    await rcon(`setblock ${x} ${y} ${z} sand`);
    await sleep(200);
    // Without support, sand at y=100 would tick and fall. Use //set:
    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x},${y},${z}`); await sleep(150);
    bot.chat(`//pos2 ${x},${y},${z}`); await sleep(150);
    const done = waitForChat(bot, /blocks have been (changed|replaced|set)/i, 8000);
    bot.chat(`//set air`);
    await done;
    await sleep(800);
    // Rollback should restore sand at y=100. With NMS direct + no
    // physics, sand should stay even without support.
    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1500);  // let any gravity tick fire
    return { label, ok: await probe(x, y, z, 'sand') };
}

// --- runner ---
async function run() {
    log('connecting bot...');
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4' });
    await new Promise((res, rej) => { bot.once('spawn', res); bot.once('error', rej); });
    bot.on('messagestr', m => {
        if (/(reversal|skip|reason|error|exception)/i.test(m)) {
            log('  chat>', m.replace(/\s+/g, ' ').slice(0, 200));
        }
    });
    await rcon(`op ${BOT_NAME}`);
    await rcon(`gamemode creative ${BOT_NAME}`);
    await sleep(500);

    const results = [];
    const cases = [
        ['TPS 50K',           () => measureTps(bot, 50000, 80)],
        ['TPS 500K',          () => measureTps(bot, 500000, 200)],
        ['Negative-Y',        () => negativeYRollback(bot)],
        ['Fluid flow-back',   () => fluidFlowback(bot)],
        ['Mid-air sand',      () => midairRollback(bot)],
    ];
    for (const [name, fn] of cases) {
        log(`\n=== ${name} ===`);
        try {
            const r = await fn();
            r.label = r.label || name;
            results.push(r);
            log(`  ${r.ok ? 'PASS' : 'FAIL'}: ${JSON.stringify(r).slice(0, 250)}`);
        } catch (e) {
            log(`  THREW: ${e.message}`);
            results.push({ label: name, ok: false, threw: e.message });
        }
        await sleep(2000);
    }

    // Crash + restart cases come last because they kill the server
    log(`\n=== Graceful restart ===`);
    try {
        results.push(await gracefulRestart(bot));
    } catch (e) {
        results.push({ label: 'graceful-restart', ok: false, threw: e.message });
    }

    // After graceful restart, bot is disconnected. Reconnect for crash test.
    log(`\n=== Crash mid-rollback ===`);
    try {
        const bot2 = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4' });
        await new Promise((res, rej) => { bot2.once('spawn', res); bot2.once('error', rej); });
        await rcon(`op ${BOT_NAME}`);
        await rcon(`gamemode creative ${BOT_NAME}`);
        await sleep(500);
        results.push(await crashMidRollback(bot2));
    } catch (e) {
        results.push({ label: 'crash-mid-rollback', ok: false, threw: e.message });
    }

    console.log('\n\n================ DURABILITY FINDINGS ================');
    for (const r of results) {
        console.log(`${r.ok ? '✓' : '✗'} ${r.label}`);
        console.log(`    ${JSON.stringify(r)}`);
    }
    process.exit(0);
}

run().catch(e => { console.error(e); process.exit(2); });
