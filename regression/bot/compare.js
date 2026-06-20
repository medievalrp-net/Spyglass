// Head-to-head: Spyglass vs CoreProtect rollback + restore/undo timing.
//
// For each size N in [10K, 100K, 500K, 1M, 2.5M]:
//   - Spawn a fresh bot named cmp<size> at unique coords.
//   - /fill stone via RCON (not logged), then //replace stone air via WE
//     (logged by BOTH plugins through their EditSession integrations).
//   - Wait for the recorder queue to drain.
//   - Capture baseline TPS.
//   - Time /spyglass rollback (Spyglass restores cube to stone).
//   - Time /spyglass undo     (Spyglass replays inverse → back to air).
//   - Time /co rollback       (CoreProtect restores cube to stone).
//   - Time /co restore        (CoreProtect re-applies → back to air).
//   - Sample TPS during each op.
//
// Each bot has its own log entries, so user-scoped rollbacks
// (`p:` for Spyglass, `u:` for CoreProtect) don't cross-contaminate
// between sizes. We also use a unique Z offset per size so the cubes
// never overlap.

import mineflayer from 'mineflayer';
import net from 'net';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';
import path from 'path';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';

// Disk-footprint measurement (regression/disk.py). Which two backends the run
// is actually exercising — Spyglass on mongo|clickhouse, CoreProtect on
// sqlite|mysql — so the report records the disk those two spend on the dataset.
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const DISK_PY = path.resolve(SCRIPT_DIR, '..', 'disk.py');
const SG_BACKEND = process.env.SG_BACKEND || 'mongo';   // mongo | clickhouse | sqlite
const CP_BACKEND = process.env.CP_BACKEND || 'sqlite';  // sqlite | mysql

function measureDisk() {
    try {
        const which = `spyglass-${SG_BACKEND},cp-${CP_BACKEND}`;
        const out = execSync(
            `python3 ${JSON.stringify(DISK_PY)} --json --which ${which}`,
            { encoding: 'utf8', timeout: 30000 });
        return JSON.parse(out);
    } catch (e) {
        log('  disk measure failed:', e.message.split('\n')[0]);
        return null;
    }
}

function fmtBytes(n) {
    if (n == null) return 'n/a';
    const u = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let f = n, i = 0;
    while (f >= 1024 && i < u.length - 1) { f /= 1024; i++; }
    return i === 0 ? `${n} B` : `${f.toFixed(1)} ${u[i]}`;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

// ─── RCON helpers (lifted from big2m.js) ───────────────────────────
function packet(id, t, body) {
    const b = Buffer.from(body, 'utf8');
    const len = 4 + 4 + b.length + 2;
    const o = Buffer.alloc(4 + len);
    o.writeInt32LE(len, 0);
    o.writeInt32LE(id, 4);
    o.writeInt32LE(t, 8);
    b.copy(o, 12);
    o.writeInt16LE(0, 12 + b.length);
    return o;
}
function rcon(cmd) {
    return new Promise((res, rej) => {
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 180000 });
        let st = 0;
        const bufs = [];
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
            // Strip Minecraft chat color codes — easier downstream
            // regex matching against cleaned text.
            const body = all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
            if (st === 0) {
                if (id === -1) return rej('auth');
                st = 1;
                bufs.length = 0;
                s.write(packet(0x1337, 2, cmd));
            } else { s.end(); res(body); }
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

async function rconUp() {
    for (let i = 0; i < 90; i++) {
        try {
            const r = await rcon('list');
            if (r && r.length > 0) return true;
        } catch { }
        await sleep(2000);
    }
    return false;
}

// ─── Test config ───────────────────────────────────────────────────
// Pick cube sides so volume hits target counts within ~5%.
//   22³ = 10,648    (10K)
//   47³ = 103,823   (100K)
//   80³ = 512,000   (500K)
//  100³ = 1,000,000 (1M)
//  136³ = 2,515,456 (2.5M)
const SIZES = [
    // { name: '10K',   side: 22  },
    // { name: '100K',  side: 47  },
    // { name: '1M',    side: 100 },
    { name: '2M',    side: 126 },
    // { name: '500K',  side: 80  },
];

const X_BASE = 14000, Y_BASE = 80, Z_BASE = 14000;
const TEST_GAP = 300; // each size shifts Z by 300; max cube side is 136 so they never touch

// ─── Helpers ───────────────────────────────────────────────────────
function tpsStats(samples) {
    if (samples.length === 0) return { min: 0, avg: 0, max: 0, n: 0 };
    return {
        min: Math.min(...samples),
        avg: samples.reduce((a, b) => a + b, 0) / samples.length,
        max: Math.max(...samples),
        n: samples.length,
    };
}

// /mspt gives us tick-time averages over 5s, 10s, 1m windows —
// directly measures main-thread load. Returns the 5s avg in ms, or
// null if it didn't parse. mspt < 50 ms = healthy 20 TPS;
// mspt > 50 ms = real TPS drop.
async function msptAvg5s() {
    const s = await msptSample();
    return s == null ? null : s.avg;
}

// Parse the 5s window's avg AND max single tick. The avg smooths
// short spikes (a lone 200ms tick over ~100 ticks barely moves it),
// so the max is what actually reveals a single-tick stall. Returns
// { avg, max } in ms, or null.
async function msptSample() {
    try {
        const r = await rcon('mspt');
        // "Server tick times (avg/min/max) from last 5s, 10s, 1m:
        //  ◴ 1.7/0.6/7.4, 1.8/0.6/7.4, 1.6/0.5/21.0"  -> first triple = 5s
        const m = r.match(/([\d.]+)\/([\d.]+)\/([\d.]+),/);
        if (m) return { avg: parseFloat(m[1]), max: parseFloat(m[3]) };
    } catch { }
    return null;
}

// Compute TPS from mspt: TPS = 1000 / max(50, mspt). The MC tick
// floor is 50 ms (= 20 TPS), so mspt under 50 still means 20 TPS.
function tpsFromMspt(mspt) {
    if (mspt == null) return null;
    return 1000.0 / Math.max(50.0, mspt);
}

// Wait until the server has recovered to a healthy idle baseline
// before timing anything. The //fill of a 2M-block cube punishes
// the main thread for ~30+ s; sampling baseline before recovery
// would give us a 14-TPS "baseline" instead of the real idle value.
// Polls /mspt 5s-avg until we see {@code stableSamples} consecutive
// readings under {@code maxMspt} AND within 0.5 ms of each other,
// or {@code maxWaitMs} elapses.
async function waitForStableBaseline({ maxMspt = 5.0, stableSamples = 4, maxWaitMs = 180000 } = {}) {
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
                if (hi <= maxMspt && (hi - lo) <= 0.8) {
                    return recent.slice();
                }
            }
            log(`  baseline wait: mspt-5s avg = ${m.toFixed(2)} ms (need ${stableSamples} consecutive <= ${maxMspt} ms, span <= 0.8 ms)`);
        }
        await sleep(2000);
    }
    log(`  baseline wait: timed out after ${maxWaitMs / 1000}s, using last samples`);
    return recent;
}

async function captureBaselineTps() {
    const msptSamples = await waitForStableBaseline({ maxMspt: 5.0, stableSamples: 4, maxWaitMs: 180000 });
    if (msptSamples.length === 0) {
        return { mspt: [], tps: [20.0] };
    }
    return { mspt: msptSamples, tps: msptSamples.map(tpsFromMspt) };
}

// Run a chat command, watch chat for completion, sample TPS during.
async function timedOp(bot, command, completionRegex, timeout = 1800000) {
    const t0 = Date.now();
    const done = waitForChat(bot, completionRegex, timeout);

    // Sundial all-threads profiling forces a JVM safepoint every 5ms and
    // only runs during the SG rollback, so it inflates SG's tick times vs
    // CP. Keep it OFF for fair MSPT/worst-tick measurement; flip to the
    // regex only when specifically capturing an off-main profile.
    const profile = false;

    const tpsDuring = [];
    const msptDuring = [];
    let worstTick = 0;        // max single-tick mspt seen (5s-window max)
    let sampling = true;
    const sampler = (async () => {
        while (sampling) {
            const s = await msptSample();  // {avg, max} over the last 5s
            if (s != null) {
                msptDuring.push(s.avg);
                tpsDuring.push(tpsFromMspt(s.avg));
                if (s.max > worstTick) worstTick = s.max;
            }
            await sleep(1500);
        }
    })();

    if (profile) { try { await rcon('sundial start all 5'); } catch (e) {} }
    bot.chat(command);
    const summary = await done;
    const ms = Date.now() - t0;
    sampling = false;
    await sampler;
    if (profile) { try { await rcon('sundial stop'); } catch (e) {} }
    return { ms, summary, tpsDuring, msptDuring, worstTick };
}

const results = [];

// Unique per-script-run suffix so each compare.js invocation gets its
// own bot identity, isolating events from prior runs that still live in
// the DB. Without this, t:30m pulls in accumulated history from earlier
// tests and inflates rollback work.
const RUN_TAG = Date.now().toString(36).slice(-5);

async function runOneSize(s, idx) {
    const botName = `cmp${s.name.toLowerCase().replace(/\./g, '')}_${RUN_TAG}`;
    const x0 = X_BASE, z0 = Z_BASE + idx * TEST_GAP;
    const x1 = x0 + s.side - 1, y1 = Y_BASE + s.side - 1, z1 = z0 + s.side - 1;
    const expected = s.side ** 3;

    log(`\n══════════════════════════════════════════════`);
    log(`▶  ${s.name}: ${s.side}³ = ${expected.toLocaleString()} blocks @ ${x0},${Y_BASE},${z0}`);
    log(`══════════════════════════════════════════════`);

    // Forceload chunks BEFORE the bot connects so it spawns into loaded chunks.
    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await sleep(800);

    const bot = mineflayer.createBot({
        host: HOST, port: PORT, username: botName, version: '1.21.4',
        // The 2M //replace floods the client with block updates; parsing
        // them starves the keepalive in node's single event loop and the
        // default 30s watchdog self-disconnects (seen under ZGC, where no
        // server GC pauses pace the flood). Timing reads chat regexes
        // AFTER the drain+baseline settle, so a patient watchdog does not
        // affect measurements.
        checkTimeoutInterval: 180_000,
    });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });

    bot.on('messagestr', m => {
        if (/(reversal|completed for|chunks affected|skip|error|exception|querying|Rollback|Restore|No data found|No results|reversals)/i.test(m)) {
            log(`  [${botName}] >`, m.replace(/\s+/g, ' ').slice(0, 200));
        }
    });

    await rcon(`op ${botName}`);
    await rcon(`gamemode creative ${botName}`);
    await rcon(`tp ${botName} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);

    // /fill stone via RCON — not logged by either plugin
    log('  filling stone…');
    const fillT0 = Date.now();
    const FILL = 32768;
    const slab = Math.max(1, Math.floor(FILL / (s.side * s.side)));
    let yCursor = Y_BASE;
    while (yCursor <= y1) {
        const yEnd = Math.min(y1, yCursor + slab - 1);
        await rcon(`fill ${x0} ${yCursor} ${z0} ${x1} ${yEnd} ${z1} stone`);
        yCursor = yEnd + 1;
    }
    log(`  fill: ${Date.now() - fillT0} ms`);
    await sleep(1500);

    // //replace stone air via WorldEdit — logged by BOTH plugins
    log('  //replace stone air…');
    bot.chat(`//sel cuboid`); await sleep(300);
    bot.chat(`//pos1 ${x0},${Y_BASE},${z0}`); await sleep(300);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(300);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 900000);
    const replaceT0 = Date.now();
    bot.chat(`//replace stone air`);
    await replaceDone;
    log(`  //replace: ${Date.now() - replaceT0} ms`);

    // Generous drain wait — 2.5M events at 50-100k/s = 25-50s.
    const drainMs = Math.max(20000, Math.min(60000, expected / 50));
    log(`  draining ${drainMs} ms…`);
    await sleep(drainMs);

    // Baseline TPS
    const baseline = await captureBaselineTps();
    const msptStr = baseline.mspt.map(m => m.toFixed(2)).join(', ');
    const tpsStr = baseline.tps.map(t => t.toFixed(2)).join(', ');
    log(`  baseline mspt: [${msptStr}] ms  → TPS [${tpsStr}]`);

    const r = {
        size: s.name,
        blocks: expected,
        baselineTps: baseline,
    };

    // Disk footprint of the seeded dataset. Measured now — after the
    // //replace drained into both stores, before any rollback writes the
    // undo ledger — so it's the cost of holding `expected` events on each
    // active backend (Spyglass on SG_BACKEND, CoreProtect on CP_BACKEND).
    r.disk = measureDisk();
    if (r.disk) {
        const sg = r.disk[`spyglass-${SG_BACKEND}`];
        const cp = r.disk[`cp-${CP_BACKEND}`];
        const fmtDisk = (d) => d && !d.skipped
            ? `${fmtBytes(d.total_bytes)} (${fmtBytes(d.storage_bytes)} data + ${fmtBytes(d.index_bytes)} index)`
              + (d.objects ? ` over ${d.objects.toLocaleString()} rows` : '')
            : `n/a (${d && d.skipped ? d.skipped.slice(0, 40) : 'not measured'})`;
        log(`  disk SG·${SG_BACKEND}: ${fmtDisk(sg)}`);
        log(`  disk CP·${CP_BACKEND}: ${fmtDisk(cp)}`);
        // A backend whose row count dwarfs this run's events was not wiped
        // before the run, so its footprint reflects accumulated data, not the
        // 2M dataset — the disk figure is meaningless until the store is reset.
        for (const d of [sg, cp]) {
            if (d && d.objects && d.objects > expected * 1.5) {
                log(`  !! DISK WARNING: ${d.backend} holds ${d.objects.toLocaleString()} rows `
                    + `(>> ${expected.toLocaleString()} this run) — DB not pristine, footprint is NOT per-dataset. `
                    + `Drop/truncate the store before trusting the disk number.`);
            }
        }
    }

    // ── Spyglass rollback ─────────────────────────────────────────
    log('  /spyglass rollback');
    const sgRb = await timedOp(bot, `/spyglass rollback p:${botName} t:30m -g`,
        /(reversals|No results)/i);
    log(`  → ${sgRb.ms} ms (${(expected / (sgRb.ms / 1000)).toFixed(0)} bps)`);
    log(`     summary: ${(sgRb.summary || '(timeout)').replace(/\s+/g, ' ').slice(0, 200)}`);
    r.sgRollback = { ms: sgRb.ms, summary: sgRb.summary, tps: tpsStats(sgRb.tpsDuring), mspt: tpsStats(sgRb.msptDuring), worstTick: sgRb.worstTick };
    await sleep(4000);

    // ── Spyglass undo (chunked ledger replay, #17) ───────────────
    // The bot is the rollback's operator, so its ledger holds the op.
    // Replay streams ~25K-effect chunks: ledger reads + chunked
    // applies, never the whole inverse list in heap.
    log('  /spyglass undo…');
    const sgUndo = await timedOp(bot, '/spyglass undo',
        /(reversals|no valid actions|Undo failed)/i);
    log(`  → ${sgUndo.ms} ms (${(expected / (sgUndo.ms / 1000)).toFixed(0)} bps)`);
    log(`     summary: ${(sgUndo.summary || '(timeout)').replace(/\s+/g, ' ').slice(0, 200)}`);
    r.sgUndo = { ms: sgUndo.ms, summary: sgUndo.summary, tps: tpsStats(sgUndo.tpsDuring), mspt: tpsStats(sgUndo.msptDuring), worstTick: sgUndo.worstTick };
    await sleep(4000);

    // After SG rollback the cube is stone; after the undo it is air
    // again. The /fill below is then a near-no-op, kept as a reset so
    // CP still gets real block-write work even on runs where the undo
    // failed — otherwise CP iterates its log but writes no blocks
    // (already-stone), measuring only its scan path, not its apply
    // path.
    log('  /fill air (reset for CP test)…');
    {
        let yc = Y_BASE;
        const slab2 = Math.max(1, Math.floor(32768 / (s.side * s.side)));
        while (yc <= y1) {
            const yEnd = Math.min(y1, yc + slab2 - 1);
            await rcon(`fill ${x0} ${yc} ${z0} ${x1} ${yEnd} ${z1} air`);
            yc = yEnd + 1;
        }
    }
    await sleep(2000);

    // ── CoreProtect rollback ─────────────────────────────────────
    // After SG undo, the cube is air again. CP's log still has the
    // original break events from //replace, so /co rollback restores
    // to stone. CP completion message: "Rollback completed for "X"."
    log('  /co rollback');
    const cpRb = await timedOp(bot, `/co rollback t:30m u:${botName} r:#global`,
        /(Rollback completed|No data found)/i);
    log(`  → ${cpRb.ms} ms (${(expected / (cpRb.ms / 1000)).toFixed(0)} bps)`);
    log(`     summary: ${(cpRb.summary || '(timeout)').replace(/\s+/g, ' ').slice(0, 200)}`);
    r.cpRollback = { ms: cpRb.ms, summary: cpRb.summary, tps: tpsStats(cpRb.tpsDuring), mspt: tpsStats(cpRb.msptDuring), worstTick: cpRb.worstTick };
    await sleep(4000);

    // ── CoreProtect restore (re-apply the breaks → back to air) ──
    log('  /co restore');
    const cpRestore = await timedOp(bot, `/co restore t:30m u:${botName} r:#global`,
        /(Restore completed|No data found)/i);
    log(`  → ${cpRestore.ms} ms (${(expected / (cpRestore.ms / 1000)).toFixed(0)} bps)`);
    log(`     summary: ${(cpRestore.summary || '(timeout)').replace(/\s+/g, ' ').slice(0, 200)}`);
    r.cpRestore = { ms: cpRestore.ms, summary: cpRestore.summary, tps: tpsStats(cpRestore.tpsDuring), mspt: tpsStats(cpRestore.msptDuring), worstTick: cpRestore.worstTick };
    await sleep(2000);

    bot.quit();
    await sleep(2000);
    await rcon(`forceload remove ${x0} ${z0} ${x1} ${z1}`);

    results.push(r);
    return r;
}

function fmtMs(ms) {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    const m = Math.floor(ms / 60000), s = Math.floor((ms % 60000) / 1000);
    return `${m}m${s}s`;
}

function printReport() {
    log('\n\n╔══════════════════════════════════════════════════════════════════════════════════════╗');
    log('║ COMPARISON RESULTS                                                                   ║');
    log('╚══════════════════════════════════════════════════════════════════════════════════════╝');
    log('');
    log('Size  | Blocks    | SG rollback    | SG undo        | CP rollback    | CP restore');
    log('------|-----------|----------------|----------------|----------------|----------------');
    for (const r of results) {
        const fmt = (op) => {
            if (!op) return 'n/a            ';
            const bps = (r.blocks / (op.ms / 1000)).toFixed(0).padStart(7);
            const time = fmtMs(op.ms).padStart(6);
            return `${time} ${bps}bps`;
        };
        const fmtSize = r.size.padEnd(5);
        const fmtBlk = r.blocks.toLocaleString().padStart(9);
        log(`${fmtSize} | ${fmtBlk} | ${fmt(r.sgRollback)} | ${fmt(r.sgUndo)} | ${fmt(r.cpRollback)} | ${fmt(r.cpRestore)}`);
    }
    log('');
    log('mspt-5s-AVG during op (max-of-avgs / avg ms — SMOOTHS single-tick spikes):');
    log('Size  | SG rollback         | SG undo             | CP rollback         | CP restore');
    log('------|---------------------|---------------------|---------------------|----------------');
    for (const r of results) {
        const fmt = (op) => {
            if (!op || !op.mspt || op.mspt.n === 0) return 'n/a                ';
            return `${op.mspt.max.toFixed(1).padStart(5)}/${op.mspt.avg.toFixed(1).padEnd(5)} ms (n=${op.mspt.n.toString().padEnd(2)})`;
        };
        log(`${r.size.padEnd(5)} | ${fmt(r.sgRollback)} | ${fmt(r.sgUndo)} | ${fmt(r.cpRollback)} | ${fmt(r.cpRestore)}`);
    }
    log('');
    log('WORST SINGLE TICK during op (mspt 5s-window max — > 50 ms = a real TPS dip that tick):');
    log('Size  | SG rollback     | SG undo         | CP rollback     | CP restore');
    log('------|-----------------|-----------------|-----------------|----------------');
    for (const r of results) {
        const fmt = (op) => {
            if (!op || op.worstTick == null) return 'n/a            ';
            return `${op.worstTick.toFixed(1).padStart(7)} ms    `;
        };
        log(`${r.size.padEnd(5)} | ${fmt(r.sgRollback)} | ${fmt(r.sgUndo)} | ${fmt(r.cpRollback)} | ${fmt(r.cpRestore)}`);
    }
    log('');
    log('TPS during op (min / avg, computed from mspt — capped at 20):');
    log('Size  | SG rollback     | SG undo         | CP rollback     | CP restore');
    log('------|-----------------|-----------------|-----------------|----------------');
    for (const r of results) {
        const fmt = (op) => {
            if (!op || op.tps.n === 0) return 'n/a            ';
            return `${op.tps.min.toFixed(1).padStart(4)}/${op.tps.avg.toFixed(1).padEnd(4)} (n=${op.tps.n.toString().padEnd(2)})`;
        };
        log(`${r.size.padEnd(5)} | ${fmt(r.sgRollback)} | ${fmt(r.sgUndo)} | ${fmt(r.cpRollback)} | ${fmt(r.cpRestore)}`);
    }
    log('');
    log(`DISK FOOTPRINT of the seeded dataset (data + index where applicable):`);
    log(`Size  | Blocks    | Spyglass · ${SG_BACKEND.padEnd(10)} | CoreProtect · ${CP_BACKEND}`);
    log('------|-----------|--------------------------------|--------------------------------');
    for (const r of results) {
        const cell = (d) => {
            if (!d || d.skipped) return 'n/a                           ';
            const extra = d.compression_ratio ? ` ${d.compression_ratio}x` : '';
            const idx = d.index_bytes ? ` +${fmtBytes(d.index_bytes)} idx` : '';
            return `${fmtBytes(d.total_bytes)}${idx}${extra}`.padEnd(30);
        };
        const sg = r.disk ? r.disk[`spyglass-${SG_BACKEND}`] : null;
        const cp = r.disk ? r.disk[`cp-${CP_BACKEND}`] : null;
        log(`${r.size.padEnd(5)} | ${r.blocks.toLocaleString().padStart(9)} | ${cell(sg)} | ${cell(cp)}`);
    }
}

// ─── Main ──────────────────────────────────────────────────────────
(async () => {
    log('Waiting for RCON…');
    if (!await rconUp()) {
        log('FATAL: RCON not up after ~3 minutes');
        process.exit(1);
    }
    log('RCON up.');

    for (let i = 0; i < SIZES.length; i++) {
        try {
            await runOneSize(SIZES[i], i);
        } catch (e) {
            log(`! ${SIZES[i].name} FAILED:`, e.message);
            results.push({
                size: SIZES[i].name,
                blocks: SIZES[i].side ** 3,
                error: e.message,
            });
        }
        // Print intermediate report after each size in case the user
        // wants to peek mid-run.
        printReport();
    }

    printReport();
    process.exit(0);
})().catch(e => {
    log('FATAL', e?.stack || e);
    process.exit(2);
});
