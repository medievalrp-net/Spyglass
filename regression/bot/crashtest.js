// Standalone crash/restart test. Uses RCON only (no mineflayer for the
// kill phase) to avoid bot-disconnect cascades crashing the test
// runner. Bot is used for the //replace setup; for the kill+verify
// phases we use RCON.
//
// Test scenarios:
//   A) /stop mid-rollback (graceful) — should drain WAL, save chunks
//   B) SIGKILL mid-rollback (hard) — should leave partial state
// In both cases, verify:
//   - Server restarts cleanly (no corruption)
//   - Re-running the rollback completes the remaining cells
//   - No stuck data, no exceptions

import mineflayer from 'mineflayer';
import net from 'net';
import fs from 'fs';
import { spawn, execSync } from 'child_process';

const HOST = '127.0.0.1';
const PORT = 25566;
const RCON_PORT = 25576;
const RCON_PASS = 'test123';
const BOT_NAME = 'rolltest';
const SERVER_DIR = process.env.RP_SERVER || new URL('../../../RP_Server', import.meta.url).pathname;

function rcon(cmd) {
    return new Promise((resolve, reject) => {
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 5000 });
        let stage = 0;
        const buffers = [];
        s.on('error', reject);
        s.on('timeout', () => { s.destroy(); reject(new Error('rcon timeout')); });
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

async function rconUp(timeoutMs = 120000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        try {
            // /list output varies (vanilla "There are X..." vs custom
            // plugin-overridden formats like "Online Players (X)").
            // Any non-empty response means RCON is up.
            const r = await rcon('list');
            if (r && r.length > 0) return true;
        } catch { }
        await sleep(2000);
    }
    return false;
}

async function setupCube(bot, x0, y0, z0, side, label) {
    const x1 = x0 + side - 1, y1 = y0 + side - 1, z1 = z0 + side - 1;
    log(`[${label}] setup ${side}^3 at (${x0},${y0},${z0})`);
    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await rcon(`tp ${BOT_NAME} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);
    const FILL_CAP = 32768;
    const slab = Math.max(1, Math.floor(FILL_CAP / (side * side)));
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
    await sleep(3000);
    return { x0, y0, z0, x1, y1, z1, side };
}

async function sampleStone(bb) {
    let stone = 0, scanned = 0;
    const total = bb.side * bb.side * bb.side;
    const stride = Math.max(1, Math.floor(total / 32));
    for (let i = 0; i < total && scanned < 32; i += stride) {
        const dx = i % bb.side, rest = Math.floor(i / bb.side);
        const dy = rest % bb.side, dz = Math.floor(rest / bb.side);
        if (await probe(bb.x0 + dx, bb.y0 + dy, bb.z0 + dz, 'stone')) stone++;
        scanned++;
    }
    return { stone, scanned };
}

async function runScenario(label, killer) {
    log(`\n=== ${label} ===`);

    // Connect bot, set up cube, trigger rollback (don't await), then kill
    let bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4' });
    await new Promise((res, rej) => { bot.once('spawn', res); bot.once('error', rej); });
    bot.on('error', () => { });  // swallow disconnect noise
    await rcon(`op ${BOT_NAME}`);
    await rcon(`gamemode creative ${BOT_NAME}`);
    await sleep(500);

    const baseY = label.includes('GRACEFUL') ? 80 : 200;
    const baseX = label.includes('GRACEFUL') ? 8000 : 9000;
    const baseZ = label.includes('GRACEFUL') ? 8000 : 9000;
    const side = 50;  // 125k cells
    const bb = await setupCube(bot, baseX, baseY, baseZ, side, label);

    bot.chat(`/spyglass rollback p:${BOT_NAME} t:5m -g`);
    log(`[${label}] rollback issued, waiting 3s before kill`);
    await sleep(3000);

    const midSample = await sampleStone(bb);
    log(`[${label}] mid-rollback: stone=${midSample.stone}/${midSample.scanned}`);

    log(`[${label}] killing server...`);
    bot.quit();
    await sleep(500);
    await killer();
    await sleep(2000);

    log(`[${label}] restarting server...`);
    spawn('bash', ['-c',
        `cd ${SERVER_DIR} && nohup ./start-testable.sh > boot-recover.log 2>&1 &`],
        { detached: true, stdio: 'ignore' }).unref();
    if (!await rconUp(120000)) {
        return { label, ok: false, reason: 'server did not come back up' };
    }
    log(`[${label}] back up`);

    // Sample post-restart state
    await rcon(`forceload add ${bb.x0} ${bb.z0} ${bb.x1} ${bb.z1}`);
    await sleep(1000);
    const postSample = await sampleStone(bb);
    log(`[${label}] post-restart: stone=${postSample.stone}/${postSample.scanned}`);

    // Re-run rollback to finish
    bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4' });
    await new Promise((res, rej) => { bot.once('spawn', res); bot.once('error', rej); });
    bot.on('error', () => { });
    await rcon(`op ${BOT_NAME}`);
    await rcon(`gamemode creative ${BOT_NAME}`);
    await rcon(`tp ${BOT_NAME} ${bb.x0 + 0.5} ${bb.y1 + 5} ${bb.z0 + 0.5}`);
    await sleep(2000);
    log(`[${label}] re-running rollback...`);
    const rb2Done = waitForChat(bot, /(reversals|No results)/i, 300000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:30m -g`);
    await rb2Done;
    await sleep(2000);
    const finalSample = await sampleStone(bb);
    log(`[${label}] post-recovery: stone=${finalSample.stone}/${finalSample.scanned}`);
    bot.quit();
    await sleep(500);

    return {
        label,
        ok: finalSample.stone === finalSample.scanned,
        midSample, postSample, finalSample,
    };
}

async function run() {
    const results = [];

    // Scenario A: graceful stop via RCON
    try {
        results.push(await runScenario('GRACEFUL stop', async () => {
            await rcon('stop').catch(() => { });
            // Wait for the JVM to actually exit
            for (let i = 0; i < 60; i++) {
                try { await rcon('list'); await sleep(1000); }
                catch { return; }
            }
        }));
    } catch (e) {
        results.push({ label: 'GRACEFUL stop', ok: false, threw: e.message });
    }

    await sleep(3000);

    // Scenario B: SIGKILL (no graceful save, no flush)
    try {
        results.push(await runScenario('CRASH (SIGKILL)', async () => {
            try { execSync('pkill -9 -f paper.jar'); } catch { }
            // Wait for the process to actually be gone
            for (let i = 0; i < 30; i++) {
                try {
                    execSync('pgrep -f paper.jar');
                    await sleep(500);
                } catch { return; }
            }
        }));
    } catch (e) {
        results.push({ label: 'CRASH (SIGKILL)', ok: false, threw: e.message });
    }

    console.log('\n\n================ CRASH/RESTART FINDINGS ================');
    for (const r of results) {
        console.log(`${r.ok ? '✓' : '✗'} ${r.label}`);
        console.log(`   ${JSON.stringify(r)}`);
    }
    process.exit(0);
}

run().catch(e => { console.error(e); process.exit(2); });
