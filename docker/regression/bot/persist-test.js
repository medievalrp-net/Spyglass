// Durability check: does a /spyglass rollback SURVIVE a chunk
// save→unload→reload, or is it only an in-memory write that's lost
// when the chunk leaves memory?
//
//   1. fill stone, //replace air (logged), rollback -> stone (in mem)
//   2. read (expect stone)            : in-memory rollback worked
//   3. save-all flush                 : persist dirty chunks to disk
//   4. bot quits + forceload remove   : let the chunk UNLOAD
//   5. forceload add (reload on disk) : bring it back from the region file
//   6. read (stone? persisted : air?) : the verdict

import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);

function packet(id, t, body) {
    const b = Buffer.from(body, 'utf8'); const len = 4 + 4 + b.length + 2;
    const o = Buffer.alloc(4 + len);
    o.writeInt32LE(len, 0); o.writeInt32LE(id, 4); o.writeInt32LE(t, 8);
    b.copy(o, 12); o.writeInt16LE(0, 12 + b.length); return o;
}
function rcon(cmd) {
    return new Promise((res, rej) => {
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 });
        let st = 0; const bufs = [];
        s.on('error', rej); s.on('timeout', () => { s.destroy(); rej(new Error('rcon timeout')); });
        s.on('connect', () => s.write(packet(0x1337, 3, PASS)));
        s.on('data', c => {
            bufs.push(c); const all = Buffer.concat(bufs);
            if (all.length < 4) return; const len = all.readInt32LE(0);
            if (all.length < len + 4) return; const id = all.readInt32LE(4);
            const body = all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
            if (st === 0) { if (id === -1) return rej('auth'); st = 1; bufs.length = 0; s.write(packet(0x1337, 2, cmd)); }
            else { s.end(); res(body); }
        });
    });
}
function waitForChat(bot, re, timeout) {
    return new Promise(resolve => {
        const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); resolve(m); } };
        bot.on('messagestr', h);
        setTimeout(() => { bot.removeListener('messagestr', h); resolve(null); }, timeout);
    });
}
async function readBlock(x, y, z) {
    if (/passed/i.test(await rcon(`execute if block ${x} ${y} ${z} minecraft:stone`))) return 'stone';
    if (/passed/i.test(await rcon(`execute if block ${x} ${y} ${z} minecraft:air`))) return 'air';
    return 'other/unloaded';
}

const BOT = 'pst' + Date.now().toString(36).slice(-4);
const SIDE = 8;                              // 512 blocks, small & fast
const X0 = 12000, Y0 = 80, Z0 = 12000;
const X1 = X0 + SIDE - 1, Y1 = Y0 + SIDE - 1, Z1 = Z0 + SIDE - 1;
const PX = X0 + 4, PY = Y0 + 4, PZ = Z0 + 4; // probe point (centre)

(async () => {
    log(`Durability test, bot ${BOT}, ${SIDE}³ cube @ ${X0},${Y0},${Z0}, probe (${PX},${PY},${PZ})`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`); await sleep(800);
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${X0 + 4.5} ${Y1 + 6} ${Z0 + 4.5}`); await sleep(2500);

    log('fill stone + //replace stone air…');
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y1} ${Z1} stone`); await sleep(1000);
    bot.chat('//sel cuboid'); await sleep(250);
    bot.chat(`//pos1 ${X0},${Y0},${Z0}`); await sleep(250);
    bot.chat(`//pos2 ${X1},${Y1},${Z1}`); await sleep(250);
    const rep = waitForChat(bot, /blocks have been replaced/i, 30000);
    bot.chat('//replace stone air'); await rep;
    await sleep(3000);
    log(`  after //replace, probe = ${await readBlock(PX, PY, PZ)} (expect air)`);

    const NOSAVE = process.argv[2] === 'nosave';
    if (NOSAVE) {
        // Persist the air baseline and CLEAR the dirty flag set by
        // //replace, so the only thing that can re-dirty the chunk is the
        // rollback itself. This isolates whether the rollback marks the
        // chunk unsaved (without it, a passive unload won't save).
        log('nosave mode: save-all flush to lock in the AIR baseline (clears //replace dirty)…');
        await rcon('save-all flush'); await sleep(4000);
        log(`  baseline locked, probe = ${await readBlock(PX, PY, PZ)} (expect air)`);
    }

    log('rollback…');
    const done = waitForChat(bot, /(reversals|No results)/i, 30000);
    bot.chat(`/spyglass rollback p:${BOT} t:5m -g`);
    const sum = await done;
    log(`  summary: ${(sum || '(timeout)').replace(/\s+/g, ' ').trim()}`);
    await sleep(1500);
    const inMem = await readBlock(PX, PY, PZ);
    log(`  after rollback (in memory), probe = ${inMem} (expect stone)`);

    if (NOSAVE) {
        log('NOT saving after rollback — passive unload must save it via the dirty flag…');
    } else {
        log('save-all flush (persist) …');
        log('  ' + (await rcon('save-all flush')).replace(/\s+/g, ' ').trim());
        await sleep(4000);
    }

    log('bot quit + forceload remove → unload the chunk…');
    bot.quit(); await sleep(2500);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(6000);   // give the chunk time to actually unload

    log('forceload add → reload the chunk from disk…');
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(4000);
    const onDisk = await readBlock(PX, PY, PZ);
    log(`  after save→unload→reload, probe = ${onDisk}`);

    log('\n──────── DURABILITY VERDICT ────────');
    log(`  in-memory after rollback : ${inMem}`);
    log(`  after reload from disk   : ${onDisk}`);
    const durable = inMem === 'stone' && onDisk === 'stone';
    log(`  RESULT: ${durable ? 'DURABLE — rollback persisted to disk.'
        : (inMem === 'stone' ? 'NOT DURABLE — rollback was lost on chunk unload (in-memory only).'
            : 'INCONCLUSIVE — rollback did not apply in memory; see log.')}`);

    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(durable ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
