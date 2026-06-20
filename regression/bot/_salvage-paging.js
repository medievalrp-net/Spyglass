// Proves salvage grouping + pagination: one rollback destroys 50 chests, which
// must (a) group under a single rollback in the GUI, and (b) paginate (45 per
// page -> 2 pages) at the container level, with working Next/Prev/Back.
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const CH = 'http://localhost:8123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);

const N = 50;                                  // chests to destroy
const X0 = 30000, Z0 = 30000, GY0 = 72, GY1 = 74, Y = 73;
const X1 = X0 + 9, Z1 = Z0 + 4;                // 10 x 5 = 50 cells at Y
const cell = i => [X0 + (i % 10), Y, Z0 + Math.floor(i / 10)];

function packet(id, t, body) {
    const b = Buffer.from(body, 'utf8'); const len = 4 + 4 + b.length + 2;
    const o = Buffer.alloc(4 + len); o.writeInt32LE(len, 0); o.writeInt32LE(id, 4); o.writeInt32LE(t, 8);
    b.copy(o, 12); o.writeInt16LE(0, 12 + b.length); return o;
}
function rcon(cmd) {
    return new Promise((res, rej) => {
        const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 });
        let st = 0; const bufs = [];
        s.on('error', rej); s.on('timeout', () => { s.destroy(); rej(new Error('rcon timeout')); });
        s.on('connect', () => s.write(packet(1, 3, PASS)));
        s.on('data', c => {
            bufs.push(c); const all = Buffer.concat(bufs); if (all.length < 4) return;
            const len = all.readInt32LE(0); if (all.length < len + 4) return; const id = all.readInt32LE(4);
            const body = all.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
            if (st === 0) { if (id === -1) return rej('auth'); st = 1; bufs.length = 0; s.write(packet(1, 2, cmd)); } else { s.end(); res(body); }
        });
    });
}
const ch = async sql => (await (await fetch(`${CH}/`, { method: 'POST', body: sql })).text()).trim();
const BOT = 'pag' + Date.now().toString(36).slice(-4);
const results = [];
const check = (name, ok, detail) => { results.push({ name, ok }); log(`  ${ok ? 'PASS' : 'FAIL'} — ${name}${detail ? ' :: ' + detail : ''}`); };

(async () => {
    log(`grouping+pagination: ${N} chests, one rollback; bot=${BOT}`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`); await sleep(1500);
    await rcon(`fill ${X0} ${GY0} ${Z0} ${X1} ${GY1} ${Z1} stone`);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${X0} ${GY1 + 8} ${Z0}`); await sleep(2500);
    try { await bot.waitForChunksToLoad(); } catch { } await sleep(1000);

    // grief (logged)
    bot.chat('//sel cuboid'); await sleep(120);
    bot.chat(`//pos1 ${X0},${GY0},${Z0}`); await sleep(120);
    bot.chat(`//pos2 ${X1},${GY1},${Z1}`); await sleep(120);
    bot.chat('//set air'); await sleep(5000);

    log(`placing ${N} loaded chests…`);
    for (let i = 0; i < N; i++) {
        const [x, y, z] = cell(i);
        await rcon(`setblock ${x} ${y} ${z} chest`);
        await rcon(`data merge block ${x} ${y} ${z} {Items:[{Slot:0b,id:"minecraft:diamond",count:${1 + (i % 64)}}]}`);
    }
    await sleep(800);

    bot.chat(`/sg rollback p:${BOT} t:1h -g`); await sleep(8000);

    log('── Verifying ──');
    const count = await ch(`SELECT count() FROM spyglass.spyglass_salvage FINAL WHERE deleted=0 AND operator_name='${BOT}'`);
    const groups = await ch(`SELECT count(DISTINCT rollback_op_id) FROM spyglass.spyglass_salvage FINAL WHERE deleted=0 AND operator_name='${BOT}'`);
    check('GROUPING: all 50 chests captured', count === String(N), `count=${count}`);
    check('GROUPING: under exactly one rollback', groups === '1', `distinct rollback ids=${groups}`);

    // ---- GUI pagination ----
    const waitWin = ms => Promise.race([new Promise(r => bot.once('windowOpen', r)), sleep(ms).then(() => null)]);
    const filled = s => bot.currentWindow && bot.currentWindow.slots[s] != null;
    const nameAt = s => (bot.currentWindow && bot.currentWindow.slots[s]) ? bot.currentWindow.slots[s].customName || bot.currentWindow.slots[s].name : '';
    try {
        bot.chat('/sg inventory');
        await waitWin(8000);                              // rollbacks
        await sleep(600);
        // the single rollback icon should advertise 50 containers
        const rbLore = JSON.stringify(bot.currentWindow?.slots?.[0] || {});
        check('GUI: rollbacks level shows a group', filled(0), 'rollback icon present');
        bot.clickWindow(0, 0, 0); await waitWin(8000); await sleep(600); // -> chests page 1

        const mat = s => bot.currentWindow?.slots?.[s]?.name;  // arrow = nav button, pane = filler
        check('GUI: chests page 1 is full (45 items)', filled(0) && filled(44), `slot0=${filled(0)} slot44=${filled(44)}`);
        check('GUI: chests page 1 has a Next button (slot 50)', mat(50) === 'arrow', `slot50=${mat(50)}`);
        check('GUI: chests page 1 has no Prev button (slot 48)', mat(48) !== 'arrow', `slot48=${mat(48)}`);

        bot.clickWindow(50, 0, 0); await waitWin(8000); await sleep(600); // -> chests page 2
        check('GUI: chests page 2 shows the remaining 5', filled(0) && filled(4) && !filled(5), `slot0=${filled(0)} slot4=${filled(4)} slot5=${filled(5)}`);
        check('GUI: chests page 2 has a Prev button (slot 48)', mat(48) === 'arrow', `slot48=${mat(48)}`);
        check('GUI: chests page 2 has a Back button (slot 45)', mat(45) === 'arrow', `slot45=${mat(45)}`);

        // extract from page 2 to prove cross-page extraction
        bot.clickWindow(0, 0, 0); await waitWin(8000); await sleep(600); // open a container
        bot.clickWindow(0, 0, 0); await sleep(1200);                     // take its stack
        try { bot.closeWindow(bot.currentWindow); } catch { }
        await sleep(2000);
        const held = bot.inventory.items().filter(i => /diamond/.test(i.name)).reduce((s, i) => s + i.count, 0);
        check('GUI: extracted a container from page 2', held > 0, `diamonds held=${held}`);
        const after = await ch(`SELECT count() FROM spyglass.spyglass_salvage FINAL WHERE deleted=0 AND operator_name='${BOT}'`);
        check('GUI: that container removed from the store', after === String(N - 1), `count now=${after} (expect ${N - 1})`);
    } catch (e) {
        check('GUI navigation', false, 'error: ' + (e?.message || e));
    }

    const passed = results.filter(r => r.ok).length;
    log(`\n──────── GROUPING + PAGINATION: ${passed}/${results.length} passed ────────`);
    log(`  VERDICT: ${results.every(r => r.ok) ? 'PASS' : 'FAIL'}`);

    bot.quit(); await sleep(800);
    await rcon(`fill ${X0} ${GY0} ${Z0} ${X1} ${GY1} ${Z1} air`);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(results.every(r => r.ok) ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
