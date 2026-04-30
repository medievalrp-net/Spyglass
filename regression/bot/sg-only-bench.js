// SG-only bench at 10K / 100K / 1M / 2M.
// Captures wall time + mspt-during-rollback so we can compute TPS
// impact directly. Skips CoreProtect entirely — for cross-plugin
// comparisons run compare.js.
//
// For each size: //replace stone → air via WE (logs break events),
// wait for mspt baseline to settle, then time /spyglass rollback
// while sampling mspt every 1.5 s.

import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

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
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 180000 });
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
            // Strip color codes.
            const body = all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
            if (st === 0) { st = 1; bufs.length = 0; s.write(packet(0x1337, 2, cmd)); }
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

// /mspt 5s avg → ms.
async function msptAvg5s() {
    try {
        const r = await rcon('mspt');
        const m = r.match(/([\d.]+)\/([\d.]+)\/([\d.]+),/);
        if (m) return parseFloat(m[1]);
    } catch { }
    return null;
}
const tpsFromMspt = m => (m == null ? null : 1000.0 / Math.max(50.0, m));

async function waitStableBaseline({ maxMspt = 5.0, stableSamples = 4, maxWaitMs = 180000 } = {}) {
    const t0 = Date.now();
    const recent = [];
    while (Date.now() - t0 < maxWaitMs) {
        const m = await msptAvg5s();
        if (m != null) {
            recent.push(m);
            if (recent.length > stableSamples) recent.shift();
            if (recent.length === stableSamples) {
                const lo = Math.min(...recent);
                const hi = Math.max(...recent);
                if (hi <= maxMspt && (hi - lo) <= 0.8) return recent.slice();
            }
            log(`  baseline wait: mspt-5s = ${m.toFixed(2)} ms`);
        }
        await sleep(2000);
    }
    return recent;
}

const SIZES = [
    { name: '10K',  side: 22  },   // 10,648
    { name: '100K', side: 47  },   // 103,823
    { name: '1M',   side: 100 },   // 1,000,000
    { name: '2M',   side: 126 },   // 2,000,376
];

const X_BASE = 14000, Y_BASE = 80, Z_BASE = 14000;
const TEST_GAP = 300;
const RUN_TAG = Date.now().toString(36).slice(-5);

const results = [];

async function runSize(s, idx) {
    const bot_name = `sgo${s.name.toLowerCase().replace(/\./g, '')}_${RUN_TAG}`;
    const x0 = X_BASE, z0 = Z_BASE + idx * TEST_GAP;
    const x1 = x0 + s.side - 1, y1 = Y_BASE + s.side - 1, z1 = z0 + s.side - 1;
    const expected = s.side ** 3;

    log(`\n══════════════════════════════════════════════`);
    log(`▶  ${s.name}: ${expected.toLocaleString()} blocks @ ${x0},${Y_BASE},${z0}`);
    log(`══════════════════════════════════════════════`);

    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await sleep(800);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: bot_name, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });

    bot.on('messagestr', m => {
        if (/(reversal|querying|No results|Spyglass)/i.test(m)) {
            log(`  [${bot_name}] >`, m.replace(/\s+/g, ' ').slice(0, 200));
        }
    });

    await rcon(`op ${bot_name}`);
    await rcon(`gamemode creative ${bot_name}`);
    await rcon(`tp ${bot_name} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);

    log('  fill stone…');
    const slab = Math.max(1, Math.floor(32768 / (s.side * s.side)));
    let yc = Y_BASE;
    while (yc <= y1) {
        const yEnd = Math.min(y1, yc + slab - 1);
        await rcon(`fill ${x0} ${yc} ${z0} ${x1} ${yEnd} ${z1} stone`);
        yc = yEnd + 1;
    }
    await sleep(1000);

    // Split //replace into Y-slabs so the bot stays under mineflayer's
    // keep-alive threshold during the burst of multi-block-change
    // packets the WE op blasts to the player. A single 2M-block
    // //replace ships enough packets in one go that the bot's
    // incoming queue can stall the keepalive responder past the
    // server's 30 s read timeout. Slabs of 16 Y keep each WE op
    // small enough that the bot processes packets and responds in
    // time.
    log('  //replace stone air via WE (Y-slabbed)…');
    const Y_SLAB = 16;
    for (let ys = Y_BASE; ys <= y1; ys += Y_SLAB) {
        const ye = Math.min(y1, ys + Y_SLAB - 1);
        bot.chat(`//sel cuboid`); await sleep(150);
        bot.chat(`//pos1 ${x0},${ys},${z0}`); await sleep(150);
        bot.chat(`//pos2 ${x1},${ye},${z1}`); await sleep(150);
        const slabDone = waitForChat(bot, /blocks have been (replaced|set|changed)/i, 300000);
        bot.chat(`//replace stone air`);
        await slabDone;
        await sleep(300);
    }
    await sleep(1000);

    log('  draining recorder + waiting baseline…');
    const baseline = await waitStableBaseline();
    const baselineStr = baseline.length > 0
        ? `[${baseline.map(m => m.toFixed(2)).join(', ')}] ms`
        : '(timeout)';
    log(`  baseline mspt: ${baselineStr}`);

    // Time SG rollback while sampling mspt every 1.5 s.
    log('  /spyglass rollback');
    const t0 = Date.now();
    const rbDone = waitForChat(bot, /(reversals|No results)/i, 1800000);
    const msptDuring = [];
    let sampling = true;
    const sampler = (async () => {
        while (sampling) {
            const m = await msptAvg5s();
            if (m != null) msptDuring.push(m);
            await sleep(1500);
        }
    })();
    bot.chat(`/spyglass rollback p:${bot_name} t:30m -g`);
    const summary = await rbDone;
    const wallMs = Date.now() - t0;
    sampling = false;
    await sampler;

    const min = msptDuring.length ? Math.min(...msptDuring) : 0;
    const max = msptDuring.length ? Math.max(...msptDuring) : 0;
    const avg = msptDuring.length ? msptDuring.reduce((a, b) => a + b, 0) / msptDuring.length : 0;
    const minTps = msptDuring.length ? Math.min(...msptDuring.map(m => tpsFromMspt(m))) : 20;

    log(`  → ${wallMs} ms (${(expected / (wallMs / 1000)).toFixed(0)} bps)`);
    log(`     summary: ${(summary || '(timeout)').replace(/\s+/g, ' ').slice(0, 200)}`);
    log(`     mspt during op: max ${max.toFixed(1)} / avg ${avg.toFixed(1)} ms (n=${msptDuring.length}); min TPS ${minTps.toFixed(2)}`);

    results.push({
        size: s.name, blocks: expected, wallMs, bps: expected / (wallMs / 1000),
        msptMin: min, msptMax: max, msptAvg: avg, msptN: msptDuring.length,
        minTps, summary,
    });

    bot.quit();
    await rcon(`forceload remove ${x0} ${z0} ${x1} ${z1}`);
    await sleep(2000);
}

(async () => {
    log('Waiting for RCON…');
    for (let i = 0; i < 60; i++) {
        try { const r = await rcon('list'); if (r) break; } catch { }
        await sleep(2000);
    }

    for (let i = 0; i < SIZES.length; i++) {
        try { await runSize(SIZES[i], i); }
        catch (e) { log(`! ${SIZES[i].name} FAILED:`, e.message); }
    }

    console.log();
    console.log('╔══════════════════════════════════════════════════════════════════════════════════════╗');
    console.log('║ SG-ONLY ROLLBACK RESULTS                                                              ║');
    console.log('╚══════════════════════════════════════════════════════════════════════════════════════╝');
    console.log();
    console.log('Size  | Blocks    | Wall    | bps      | mspt max/avg ms  | min TPS');
    console.log('------|-----------|---------|----------|------------------|--------');
    for (const r of results) {
        const wall = r.wallMs >= 1000 ? `${(r.wallMs / 1000).toFixed(1)}s` : `${r.wallMs}ms`;
        console.log(`${r.size.padEnd(5)} | ${r.blocks.toLocaleString().padStart(9)} | ${wall.padStart(7)} | ${r.bps.toFixed(0).padStart(8)} | ${r.msptMax.toFixed(1).padStart(5)}/${r.msptAvg.toFixed(1).padEnd(5)} ms (n=${String(r.msptN).padEnd(2)}) | ${r.minTps.toFixed(2)}`);
    }
    process.exit(0);
})().catch(e => { console.error(e); process.exit(2); });
