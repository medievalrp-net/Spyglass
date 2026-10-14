// Focused Spyglass-only benchmark: 100K + 1M rollback timing.
// Uses RCON for everything so the bot can disconnect right after //replace
// and we don't hit keepalive timeout during long drains.
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';
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

const SIZES = [
    { name: '100K', side: 47 },
    { name: '1M',   side: 100 },
];
const X_BASE = 14000, Y_BASE = 80, Z_BASE = 14000;

async function run(size, idx) {
    const botName = `bench${size.name.toLowerCase()}`;
    const x0 = X_BASE, z0 = Z_BASE + idx * 300;
    const x1 = x0 + size.side - 1, y1 = Y_BASE + size.side - 1, z1 = z0 + size.side - 1;
    log(`\n▶ ${size.name}: ${size.side}³ at ${x0},${Y_BASE},${z0}`);

    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await sleep(500);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: botName, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${botName}`);
    await rcon(`gamemode creative ${botName}`);
    await rcon(`tp ${botName} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
    await sleep(2500);

    log('  fill stone…');
    const slab = Math.max(1, Math.floor(32768 / (size.side * size.side)));
    let yc = Y_BASE;
    while (yc <= y1) {
        const yEnd = Math.min(y1, yc + slab - 1);
        await rcon(`fill ${x0} ${yc} ${z0} ${x1} ${yEnd} ${z1} stone`);
        yc = yEnd + 1;
    }
    await sleep(1500);

    log('  //replace stone air…');
    bot.chat(`//sel cuboid`); await sleep(300);
    bot.chat(`//pos1 ${x0},${Y_BASE},${z0}`); await sleep(300);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(300);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 600000);
    bot.chat(`//replace stone air`);
    await replaceDone;

    // Disconnect bot to avoid keepalive issue during drain
    bot.quit();
    await sleep(1500);

    // Drain wait — RCON-based, no bot needed
    const drainMs = Math.max(20000, Math.min(60000, (size.side ** 3) / 50));
    log(`  drain ${drainMs}ms via RCON…`);
    await sleep(drainMs);

    // Issue rollback via RCON, poll rbqueue for completion
    log('  /sg rollback p:' + botName + ' t:30m -g');
    const t0 = Date.now();
    const tps = [];
    await rcon(`sg rollback p:${botName} t:30m -g`);
    while (true) {
        try {
            const tpsR = await rcon('tps');
            const m = tpsR.match(/from last 1m, 5m, 15m:\s*([\d.]+)/);
            if (m) tps.push(parseFloat(m[1]));
        } catch { }
        try {
            const q = await rcon('sg rbqueue list');
            if (q.match(/Recent/) && !q.match(/Running:/)) break;
        } catch { }
        await sleep(1500);
        if (Date.now() - t0 > 8 * 60_000) { log('TIMEOUT'); break; }
    }
    const ms = Date.now() - t0;
    const expected = size.side ** 3;
    const bps = (expected / (ms / 1000)).toFixed(0);
    log(`  → ${ms} ms (${bps} bps)`);
    if (tps.length) {
        log(`  TPS: min=${Math.min(...tps).toFixed(2)} avg=${(tps.reduce((a, b) => a + b, 0) / tps.length).toFixed(2)} (n=${tps.length})`);
    }
    await rcon(`forceload remove ${x0} ${z0} ${x1} ${z1}`);
    return { size: size.name, blocks: expected, ms, bps, tps };
}

(async () => {
    log('=== Spyglass focused benchmark ===');
    const results = [];
    for (let i = 0; i < SIZES.length; i++) {
        results.push(await run(SIZES[i], i));
    }
    log('\n=== SUMMARY ===');
    for (const r of results) {
        log(`${r.size}: ${r.ms} ms (${r.bps} bps)`);
    }
    process.exit(0);
})().catch(e => { log('FATAL', e); process.exit(2); });
