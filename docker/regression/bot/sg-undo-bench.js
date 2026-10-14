// SG-only rollback + undo bench at 10K / 100K / 1M / 2M / 5M.
// For each size: //replace stone → air via WE (logs break events),
// then time /spyglass rollback (cube returns to stone),
// then time /spyglass undo (cube goes back to air).
// Captures wall time and mspt-during-op for both phases so we can
// see if the chunked CH undo write/read paths add per-block cost.
//
// Compared to sg-only-bench.js this is the same harness with a
// /spyglass undo step bolted onto the back of each size run.
//
// rollback-undo-cap must be ≥ the largest size, or the undo step
// will be skipped (the rollback emits "exceeded undo cap"). Bench
// runs the deployed RP_Server config; the operator bumps that to
// 10M before invoking this script.

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
    { name: '5M',   side: 171 },   // 5,000,211
];

const X_BASE = 14000, Y_BASE = 80, Z_BASE = 14000;
const TEST_GAP = 300;
const RUN_TAG = Date.now().toString(36).slice(-5);

const results = [];

async function timedOp(bot, label, command, doneRe) {
    log(`  ${label}: ${command}`);
    const t0 = Date.now();
    const done = waitForChat(bot, doneRe, 1800000);
    const msptDuring = [];
    let sampling = true;
    const sampler = (async () => {
        while (sampling) {
            const m = await msptAvg5s();
            if (m != null) msptDuring.push(m);
            await sleep(1500);
        }
    })();
    bot.chat(command);
    const summary = await done;
    const wallMs = Date.now() - t0;
    sampling = false;
    await sampler;
    const max = msptDuring.length ? Math.max(...msptDuring) : 0;
    const avg = msptDuring.length ? msptDuring.reduce((a, b) => a + b, 0) / msptDuring.length : 0;
    const minTps = msptDuring.length ? Math.min(...msptDuring.map(m => tpsFromMspt(m))) : 20;
    return { wallMs, msptMax: max, msptAvg: avg, msptN: msptDuring.length, minTps, summary };
}

async function runSize(s, idx) {
    const bot_name = `sgu${s.name.toLowerCase().replace(/\./g, '')}_${RUN_TAG}`;
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
        if (/(reversal|querying|No results|Spyglass|undo|cap)/i.test(m)) {
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

    // Phase 1: rollback (air → stone)
    const rb = await timedOp(bot, '/spyglass rollback',
        `/spyglass rollback p:${bot_name} t:30m -g`,
        /(reversals|No results)/i);
    log(`  → rollback ${rb.wallMs} ms (${(expected / (rb.wallMs / 1000)).toFixed(0)} bps)`);
    log(`     mspt ${rb.msptMax.toFixed(1)}/${rb.msptAvg.toFixed(1)} ms (n=${rb.msptN}); minTPS ${rb.minTps.toFixed(2)}`);
    log(`     summary: ${(rb.summary || '(timeout)').replace(/\s+/g, ' ').slice(0, 200)}`);

    // Wait long enough for the async undo push to drain to CH
    // before issuing /spyglass undo. The push fans out N INSERT
    // calls (N = ceil(blocks / 25k)) on the plugin's async thread,
    // each ~25 MB. At ~0.7 s per insert, an N-chunk push needs
    // ~0.7N seconds; pad generously so the bench doesn't race.
    const chunkCount = Math.max(1, Math.ceil(expected / 25_000));
    const settleMs = Math.max(3000, chunkCount * 1000 + 5000);
    log(`  settle for ${(settleMs / 1000).toFixed(0)}s (push has ${chunkCount} chunks)…`);
    await sleep(settleMs);

    // Phase 2: undo (stone → air)
    const un = await timedOp(bot, '/spyglass undo',
        `/spyglass undo`,
        /(reversals|No results|nothing to undo|cap)/i);
    log(`  → undo ${un.wallMs} ms (${(expected / (un.wallMs / 1000)).toFixed(0)} bps)`);
    log(`     mspt ${un.msptMax.toFixed(1)}/${un.msptAvg.toFixed(1)} ms (n=${un.msptN}); minTPS ${un.minTps.toFixed(2)}`);
    log(`     summary: ${(un.summary || '(timeout)').replace(/\s+/g, ' ').slice(0, 200)}`);

    results.push({
        size: s.name, blocks: expected,
        rb_wall: rb.wallMs, rb_bps: expected / (rb.wallMs / 1000),
        rb_msptMax: rb.msptMax, rb_msptAvg: rb.msptAvg, rb_minTps: rb.minTps,
        un_wall: un.wallMs, un_bps: expected / (un.wallMs / 1000),
        un_msptMax: un.msptMax, un_msptAvg: un.msptAvg, un_minTps: un.minTps,
        un_summary: un.summary,
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
    console.log('║ SG ROLLBACK + UNDO RESULTS                                                            ║');
    console.log('╚══════════════════════════════════════════════════════════════════════════════════════╝');
    console.log();
    console.log('Size  | Blocks    | Phase    | Wall    | bps      | mspt max/avg ms  | min TPS');
    console.log('------|-----------|----------|---------|----------|------------------|--------');
    for (const r of results) {
        const fmt = (ms) => ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;
        console.log(`${r.size.padEnd(5)} | ${r.blocks.toLocaleString().padStart(9)} | rollback | ${fmt(r.rb_wall).padStart(7)} | ${r.rb_bps.toFixed(0).padStart(8)} | ${r.rb_msptMax.toFixed(1).padStart(5)}/${r.rb_msptAvg.toFixed(1).padEnd(5)} ms        | ${r.rb_minTps.toFixed(2)}`);
        console.log(`${''.padEnd(5)} | ${''.padStart(9)} | undo     | ${fmt(r.un_wall).padStart(7)} | ${r.un_bps.toFixed(0).padStart(8)} | ${r.un_msptMax.toFixed(1).padStart(5)}/${r.un_msptAvg.toFixed(1).padEnd(5)} ms        | ${r.un_minTps.toFixed(2)}`);
    }
    process.exit(0);
})().catch(e => { console.error(e); process.exit(2); });
