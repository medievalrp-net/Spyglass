// Multi-edit ordering proof for the parallel-read rollback.
//
// The distribution proof edits each cell ONCE, so it can't catch an apply
// that processes events out of global time order. This one edits every cell
// THREE times, seconds apart, so the three edits land in different
// occurred-time partitions of the parallel reader. A correct rollback applies
// them newest-first across partitions, so the oldest edit's revert wins and
// every cell returns to its pre-first-edit base. A mis-ordered apply leaves
// cells stuck at an intermediate type.
//
// Usage: node _multi-edit-proof.js [SIDE]
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

const SIDE = parseInt(process.argv[2] || '48', 10);
const X0 = 28000, Y0 = 80, Z0 = 28000;
const X1 = X0 + SIDE - 1, Z1 = Z0 + SIDE - 1; // single Y-layer
const VOL = SIDE * SIDE;
const BASE = 'bedrock';                 // pre-first-edit state every cell must return to
const ROUNDS = ['stone', 'dirt', 'glass']; // three logged overwrites, oldest→newest

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
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 120000 });
        let st = 0; const bufs = [];
        s.on('error', rej);
        s.on('timeout', () => { s.destroy(); rej(new Error('rcon timeout: ' + cmd)); });
        s.on('connect', () => s.write(packet(0x1337, 3, PASS)));
        s.on('data', c => {
            bufs.push(c);
            const all = Buffer.concat(bufs);
            if (all.length < 4) return;
            const len = all.readInt32LE(0);
            if (all.length < len + 4) return;
            const id = all.readInt32LE(4);
            const body = all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
            if (st === 0) { if (id === -1) return rej('auth'); st = 1; bufs.length = 0; s.write(packet(0x1337, 2, cmd)); }
            else { s.end(); res(body); }
        });
    });
}
const countOf = s => { const m = s && s.match(/(\d[\d,]*)\s+block/i); return m ? parseInt(m[1].replace(/,/g, ''), 10) : 0; };
function waitChat(bot, re, timeout) {
    return new Promise(resolve => {
        const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); resolve(m); } };
        bot.on('messagestr', h);
        setTimeout(() => { bot.removeListener('messagestr', h); resolve(null); }, timeout);
    });
}

const BOT = 'rbm' + Date.now().toString(36).slice(-4);

(async () => {
    log(`Region ${SIDE}x${SIDE} = ${VOL.toLocaleString()} cells @ (${X0},${Y0},${Z0}); base=${BASE}, rounds=[${ROUNDS}]`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(1500);
    // Base layer, UNLOGGED (RCON). Every cell must return here after rollback.
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y0} ${Z1} ${BASE}`);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`);
    await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${X0 + SIDE / 2} ${Y0 + 4} ${Z0 + SIDE / 2}`);
    await sleep(2500);
    try { await bot.waitForChunksToLoad(); } catch { }
    await sleep(1500);
    bot.chat('//sel cuboid'); await sleep(200);
    bot.chat(`//pos1 ${X0},${Y0},${Z0}`); await sleep(200);
    bot.chat(`//pos2 ${X1},${Y0},${Z1}`); await sleep(200);

    // Three logged overwrites, ~3s apart so each lands in a distinct
    // occurred-time partition of the parallel reader.
    for (const type of ROUNDS) {
        const done = waitChat(bot, /(blocks? have been changed|operation completed|affected)/i, 120000);
        bot.chat(`//set ${type}`);
        await done;
        log(`  //set ${type} done`);
        await sleep(3000);
    }
    await sleep(6000); // let the recorder drain all three rounds

    log(`Rollback /sg rollback p:${BOT} t:1h -g …`);
    let summary = null;
    const h = m => { if (/(reversals|No results|rolled back)/i.test(m)) summary = m; };
    bot.on('messagestr', h);
    const t0 = Date.now();
    bot.chat(`/sg rollback p:${BOT} t:1h -g`);
    while (summary == null && Date.now() - t0 < 180000) await sleep(200);
    bot.removeListener('messagestr', h);
    log(`  rollback: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(timeout)'}  (${Date.now() - t0}ms)`);
    await sleep(5000);

    // Verify: every cell back to BASE. Count base cells (destructive: base->diamond),
    // and count any survivors of each round type that should be gone.
    const baseCount = countOf(await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y0} ${Z1} diamond_block replace ${BASE}`));
    const leftovers = {};
    for (const type of ROUNDS) {
        leftovers[type] = countOf(await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y0} ${Z1} air replace ${type}`));
    }
    log('\n──────── MULTI-EDIT ROLLBACK RESULT ────────');
    log(`  ${BASE.padEnd(10)} restored ${baseCount.toLocaleString().padStart(8)} / ${VOL.toLocaleString()}`);
    let stuck = 0;
    for (const type of ROUNDS) {
        if (leftovers[type] > 0) { log(`  STUCK at ${type}: ${leftovers[type].toLocaleString()} cells (ordering bug)`); stuck += leftovers[type]; }
    }
    const pass = baseCount === VOL && stuck === 0;
    log(`\n  VERDICT: ${pass
        ? 'PASS — every multi-edited cell returned to its pre-first-edit base; global order preserved across partitions.'
        : 'FAIL — cells did not return to base (' + baseCount + '/' + VOL + ', ' + stuck + ' stuck) — apply order is wrong.'}`);

    bot.quit();
    await sleep(1000);
    await rcon(`fill ${X0} ${Y0} ${Z0} ${X1} ${Y0} ${Z1} air`);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(pass ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
