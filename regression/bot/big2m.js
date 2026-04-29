// 2-million-block rollback timing test. Lays a 126^3 (~2M) cube of
// stone via /fill (silent), bot //replaces stone air (~2M logged
// breaks), then issues /spyglass rollback and times it end to end.
// Reports baseline TPS, rollback wall time, effective blocks/sec,
// and TPS dip during.

import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566;
const RCON_PORT = 25576, PASS = 'test123';
const BOT = 'rolltest';

const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

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
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 30000 });
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
            const body = all.slice(12, 12 + len - 10).toString('utf8');
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

const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
bot.on('messagestr', m => {
    if (/(reversal|skip|reason|chunk|block|error|exception|spyglass|querying)/i.test(m)) {
        log('chat>', m.replace(/\s+/g, ' ').slice(0, 250));
    }
});

await rcon(`op ${BOT}`);
await rcon(`gamemode creative ${BOT}`);
await sleep(500);

// 126^3 = 2,000,376 cells (≥ 2M)
const SIDE = 126;
const x0 = 14000, z0 = 14000, y0 = 80;
const x1 = x0 + SIDE - 1, y1 = y0 + SIDE - 1, z1 = z0 + SIDE - 1;
const expected = SIDE * SIDE * SIDE;
log(`2M test: cube ${SIDE}^3 = ${expected.toLocaleString()} blocks`);

await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
await rcon(`tp ${BOT} ${x0 + 0.5} ${y1 + 5} ${z0 + 0.5}`);
await sleep(3000);

log('filling stone (this takes a while)...');
const FILL = 32768;
const slab = Math.max(1, Math.floor(FILL / (SIDE * SIDE)));
let yCursor = y0;
const fillT0 = Date.now();
while (yCursor <= y1) {
    const yEnd = Math.min(y1, yCursor + slab - 1);
    await rcon(`fill ${x0} ${yCursor} ${z0} ${x1} ${yEnd} ${z1} stone`);
    yCursor = yEnd + 1;
}
log(`fill: ${Date.now() - fillT0} ms`);
await sleep(2000);

log('//replace stone air ...');
bot.chat(`//sel cuboid`); await sleep(300);
bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(300);
bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(300);
const replaceDone = waitForChat(bot, /blocks have been replaced/i, 600000);
const burstT0 = Date.now();
bot.chat(`//replace stone air`);
await replaceDone;
const burstMs = Date.now() - burstT0;
log(`//replace done in ${burstMs} ms`);

// Generous drain wait — 2M events at 50-100k/sec = 20-40 sec
log('waiting 45s for recorder drain...');
await sleep(45000);

// Capture baseline TPS
log('Baseline TPS:');
const baseline = [];
for (let i = 0; i < 3; i++) {
    const r = await rcon('tps');
    const m = r.match(/from last 1m, 5m, 15m:\s*[§a-f0-9]*?([\d.]+)/);
    if (m) baseline.push(parseFloat(m[1]));
    await sleep(800);
}
log(`  baseline: ${baseline.join(', ')}`);

log('issuing rollback...');
const rbT0 = Date.now();
const rbDone = waitForChat(bot, /(reversals|No results)/i, 1200000);

// Sample TPS during rollback (every 1.5s) — record min/avg
const tpsDuring = [];
let sampling = true;
const sampler = (async () => {
    while (sampling) {
        try {
            const r = await rcon('tps');
            const m = r.match(/from last 1m, 5m, 15m:\s*[§a-f0-9]*?([\d.]+)/);
            if (m) tpsDuring.push(parseFloat(m[1]));
        } catch { }
        await sleep(1500);
    }
})();

bot.chat(`/spyglass rollback p:${BOT} t:30m -g`);
const summaryLine = await rbDone;
const rbMs = Date.now() - rbT0;
sampling = false;
await sampler;

log(`SUMMARY LINE: ${summaryLine ? summaryLine.replace(/\s+/g, ' ') : '(timeout)'}`);
log(`Wall time: ${rbMs} ms (${(rbMs / 1000).toFixed(1)} s)`);
log(`Throughput: ${(expected / (rbMs / 1000)).toFixed(0)} blocks/sec`);
if (tpsDuring.length > 0) {
    const min = Math.min(...tpsDuring);
    const avg = tpsDuring.reduce((a, b) => a + b, 0) / tpsDuring.length;
    const max = Math.max(...tpsDuring);
    log(`TPS during: min=${min.toFixed(2)}, avg=${avg.toFixed(2)}, max=${max.toFixed(2)} (${tpsDuring.length} samples)`);
}

bot.quit();
process.exit(0);
