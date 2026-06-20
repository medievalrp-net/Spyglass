// Grow the CoreProtect SQLite database via repeated //replace 2.5M cycles.
// Time CP rollback at intermediate sizes to map "rollback latency vs
// DB size" curve. Each round = 2.5M block-break events ≈ 90-200 MB CP
// DB growth (depends on WAL checkpoint timing).
import mineflayer from 'mineflayer';
import net from 'net';
import fs from 'fs';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';
const RP_SERVER = process.env.RP_SERVER || new URL('../../../RP_Server', import.meta.url).pathname;
const CP_DB_PATH = RP_SERVER + '/plugins/CoreProtect/database.db';

const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);

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
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 30000 });
        let st = 0; const bufs = [];
        s.on('error', rej);
        s.on('connect', () => s.write(packet(1, 3, PASS)));
        s.on('data', c => {
            bufs.push(c);
            const all = Buffer.concat(bufs);
            if (all.length < 4) return;
            const len = all.readInt32LE(0);
            if (all.length < len + 4) return;
            if (st === 0) { st = 1; bufs.length = 0; s.write(packet(1, 2, cmd)); }
            else { res(all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '')); s.end(); }
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

const SIDE = 136;
const X_BASE = 14000, Y_BASE = 80, Z_BASE_BUILD = 12000;
const ROUNDS = parseInt(process.env.ROUNDS || '10', 10);
const SAMPLE_AT = (process.env.SAMPLE_AT || '1,2,3,5,7,10').split(',').map(s => parseInt(s, 10));

function dbSize() {
    let total = 0;
    for (const ext of ['', '-wal', '-shm']) {
        try { total += fs.statSync(CP_DB_PATH + ext).size; } catch { }
    }
    return total;
}
function fmtSize(b) {
    if (b < 1024 * 1024) return (b / 1024).toFixed(0) + ' KB';
    if (b < 1024 * 1024 * 1024) return (b / 1024 / 1024).toFixed(1) + ' MB';
    return (b / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

async function buildOneRound(round) {
    const botName = `cpgrow${round}`;
    const z0 = Z_BASE_BUILD + (round - 1) * 200;
    const x0 = X_BASE, y0 = Y_BASE;
    const x1 = x0 + SIDE - 1, y1 = y0 + SIDE - 1, z1 = z0 + SIDE - 1;

    log(`▶ round ${round}: bot=${botName} cube at ${x0},${y0},${z0}`);
    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await sleep(800);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: botName, version: '1.21.4' });
    bot.on('error', () => { });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${botName}`);
    await rcon(`gamemode creative ${botName}`);
    await rcon(`tp ${botName} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);

    const slab = Math.max(1, Math.floor(32768 / (SIDE * SIDE)));
    let yc = y0;
    while (yc <= y1) {
        const yEnd = Math.min(y1, yc + slab - 1);
        await rcon(`fill ${x0} ${yc} ${z0} ${x1} ${yEnd} ${z1} stone`);
        yc = yEnd + 1;
    }
    await sleep(1500);

    bot.chat(`//sel cuboid`); await sleep(300);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(300);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(300);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 600000);
    bot.chat(`//replace stone air`);
    await replaceDone;
    bot.quit();
    await sleep(2500);

    log(`  drain 25 s for record consumers…`);
    await sleep(25000);

    return { botName, x0, y0, z0, x1, y1, z1 };
}

async function timeCpRollback(round, area) {
    const { botName, x0, y0, z0, x1, y1, z1 } = area;

    // Wait a moment for any prior CP work to settle (no probe — that
    // creates spurious "in progress" responses).
    await sleep(5000);

    log(`  /co rollback p:${botName}, polling far-corner stone-flip`);
    const t0 = Date.now();
    const tps = [];
    await rcon(`co rollback t:30m u:${botName} r:#global`);
    let lastFmt = null;
    while (true) {
        try {
            const tpsR = await rcon('tps');
            const m = tpsR.match(/from last 1m, 5m, 15m:\s*([\d.]+)/);
            if (m) tps.push(parseFloat(m[1]));
        } catch { }
        // Probe FAR corner — last cell to flip in CP's iteration order.
        const r = await rcon(`execute if block ${x1} ${y1} ${z1} stone`);
        if (r.includes('passed')) break;
        await sleep(2000);
        // periodic progress
        const elapsedSec = Math.floor((Date.now() - t0) / 1000);
        const fmt = elapsedSec < 60 ? `${elapsedSec}s` : `${Math.floor(elapsedSec / 60)}m${elapsedSec % 60}s`;
        if (fmt !== lastFmt && elapsedSec % 30 === 0) {
            log(`    rollback in flight: ${fmt}`);
            lastFmt = fmt;
        }
        if (Date.now() - t0 > 30 * 60_000) { log(`    TIMEOUT @ 30 min`); return null; }
    }
    const ms = Date.now() - t0;

    // Reset to air for next round
    let yc = y0;
    const slab = Math.max(1, Math.floor(32768 / (SIDE * SIDE)));
    while (yc <= y1) {
        const yEnd = Math.min(y1, yc + slab - 1);
        await rcon(`fill ${x0} ${yc} ${z0} ${x1} ${yEnd} ${z1} air`);
        yc = yEnd + 1;
    }
    await rcon(`forceload remove ${x0} ${z0} ${x1} ${z1}`);

    return { ms, tps };
}

(async () => {
    log(`=== CP DB growth experiment: ${ROUNDS} rounds × 2.5M blocks each ===`);
    log(`Sample rollbacks at rounds: ${SAMPLE_AT.join(', ')}`);
    log(`Initial CP DB size: ${fmtSize(dbSize())}`);

    const results = [];
    for (let round = 1; round <= ROUNDS; round++) {
        const area = await buildOneRound(round);
        const sizeAfter = dbSize();
        log(`  round ${round} done: CP DB = ${fmtSize(sizeAfter)}`);

        let rollbackInfo = null;
        if (SAMPLE_AT.includes(round)) {
            rollbackInfo = await timeCpRollback(round, area);
            if (rollbackInfo) {
                const min = rollbackInfo.tps.length ? Math.min(...rollbackInfo.tps).toFixed(2) : 'n/a';
                const avg = rollbackInfo.tps.length ?
                    (rollbackInfo.tps.reduce((a, b) => a + b, 0) / rollbackInfo.tps.length).toFixed(2) : 'n/a';
                log(`  round ${round} CP rollback: ${(rollbackInfo.ms / 1000).toFixed(1)} s; TPS ${min}/${avg}`);
            }
        }
        results.push({
            round,
            dbBytes: sizeAfter,
            rollbackMs: rollbackInfo?.ms || null,
        });
    }

    log(`\n=== SUMMARY ===`);
    log(`round | DB size  | CP rollback time`);
    for (const r of results) {
        log(`${String(r.round).padStart(5)} | ${fmtSize(r.dbBytes).padEnd(8)} | ${r.rollbackMs ? (r.rollbackMs / 1000).toFixed(1) + ' s' : '—'}`);
    }
    process.exit(0);
})().catch(e => { log('FATAL', e.stack || e); process.exit(2); });
