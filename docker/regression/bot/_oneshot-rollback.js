// One-shot: connect a bot, fire /spyglass rollback against existing
// data (no fill / replace / drain), capture wall time + mspt during
// the apply. Used to isolate apply-phase cost from setup interference.
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';
const TARGET_BOT = process.argv[2] || 'sgo2m_04ysr';   // bot whose events we roll back
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
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 });
        let st = 0; const bufs = [];
        s.on('error', rej);
        s.on('connect', () => s.write(packet(1, 3, PASS)));
        s.on('data', c => {
            bufs.push(c);
            const all = Buffer.concat(bufs);
            if (all.length < 4) return;
            const len = all.readInt32LE(0);
            if (all.length < len + 4) return;
            const body = all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
            if (st === 0) { st = 1; bufs.length = 0; s.write(packet(1, 2, cmd)); }
            else { s.end(); res(body); }
        });
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

(async () => {
    const driverName = 'oneshot' + Date.now().toString(36).slice(-4);
    log(`Spawning ${driverName} (rollback target: p:${TARGET_BOT})`);
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: driverName, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${driverName}`);
    await sleep(1500);

    const baseline = await msptAvg5s();
    log(`baseline mspt: ${baseline} ms`);

    const samples = [];
    let sampling = true;
    const sampler = (async () => {
        while (sampling) {
            const m = await msptAvg5s();
            if (m != null) samples.push(m);
            await sleep(1000);
        }
    })();

    let summary = null;
    bot.on('messagestr', m => {
        if (/(reversals|No results)/i.test(m)) summary = m;
    });

    const t0 = Date.now();
    bot.chat(`/spyglass rollback p:${TARGET_BOT} t:30m -g`);
    log(`rollback issued; waiting for completion…`);
    while (summary == null) await sleep(500);
    const wallMs = Date.now() - t0;
    sampling = false;
    await sampler;

    const min = Math.min(...samples), max = Math.max(...samples), avg = samples.reduce((a, b) => a + b, 0) / samples.length;
    const minTps = Math.min(...samples.map(m => 1000 / Math.max(50, m)));

    log(`DONE in ${wallMs} ms`);
    log(`summary: ${summary}`);
    log(`mspt during op: max ${max.toFixed(1)} / avg ${avg.toFixed(1)} ms (n=${samples.length}); min TPS ${minTps.toFixed(2)}`);

    bot.quit();
    process.exit(0);
})().catch(e => { console.error(e); process.exit(2); });
