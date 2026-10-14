// Spark-profile a 2M rollback. Bot starts /spark profiler, runs the
// rollback, stops the profiler. Captures the spark.lucko.me URL from
// chat output.
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';
const TARGET = process.argv[2] || 'sgo2m_04ysr';
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

(async () => {
    const driver = 'sparkprof' + Date.now().toString(36).slice(-4);
    log(`Spawning ${driver}; will profile rollback p:${TARGET}`);
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: driver, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${driver}`);
    await sleep(1500);

    // Capture spark messages — they post the upload URL in chat
    let sparkUrl = null;
    let rbSummary = null;
    bot.on('messagestr', m => {
        const urlMatch = m.match(/(https:\/\/spark\.lucko\.me\/\S+)/);
        if (urlMatch) sparkUrl = urlMatch[1];
        if (/(reversals|No results)/i.test(m)) rbSummary = m;
        // log all spark / rollback chat for visibility
        if (/(spark|reversal|profiler|completed|interval|samples)/i.test(m)) {
            console.log(`  [chat]`, m.replace(/\s+/g, ' ').slice(0, 160));
        }
    });

    log('Starting spark profiler (all threads, 1ms interval)…');
    bot.chat('/spark profiler --start --thread * --interval 1');
    await sleep(2500);

    log(`Issuing /spyglass rollback p:${TARGET}…`);
    const t0 = Date.now();
    bot.chat(`/spyglass rollback p:${TARGET} t:30m -g`);
    while (rbSummary == null) await sleep(500);
    const wallMs = Date.now() - t0;
    log(`Rollback done: ${wallMs} ms — ${rbSummary}`);

    // Let spark capture a couple more seconds of post-rollback idle so
    // the trace clearly shows the rollback boundary.
    await sleep(2000);

    log('Stopping spark profiler (uploads to spark.lucko.me)…');
    bot.chat('/spark profiler --stop');
    // wait up to 60s for url
    const tStop = Date.now();
    while (sparkUrl == null && Date.now() - tStop < 60000) await sleep(500);

    log(`spark URL: ${sparkUrl || '(not captured — check server log)'}`);
    bot.quit();
    process.exit(0);
})().catch(e => { console.error(e); process.exit(2); });
