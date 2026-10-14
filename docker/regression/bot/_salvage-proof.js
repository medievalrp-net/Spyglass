// End-to-end proof for container salvage (#76).
//
// Scenario: a stone region is griefed (bot //set air, LOGGED). Then a VICTIM
// chest full of diamonds is placed inside the griefed region, and a SURVIVOR
// chest full of emeralds is placed in the SAME CHUNK but above the grief (a
// cell the rollback will not touch). The rollback restores stone over the
// region — destroying the victim chest but not the survivor.
//
// Verifies, all server-side:
//   1. CAPTURE   — the victim's diamonds land in the salvage store (ClickHouse).
//   2. PRECISION — exactly ONE salvage row; the survivor's emeralds are NOT
//                  salvaged (capture-then-verify discards survivors → no dupe).
//   3. WORLD     — victim cell restored to stone; survivor chest intact.
//   4. LISTING   — /sg inventory (console text) shows the diamonds.
//   5. EXTRACT   — a player opens the GUI, takes the diamonds (into their
//                  inventory), the snapshot empties, and a salvage-withdraw
//                  event is logged.
import mineflayer from 'mineflayer';
import vec3 from 'vec3';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const CH = 'http://localhost:8123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);

// Fresh region, away from the other proofs (24000 / 26000).
const X0 = 28000, Z0 = 28000, GY0 = 72, GY1 = 76;   // grief box (stone -> air)
const X1 = X0 + 9, Z1 = Z0 + 9;
const VX = X0 + 5, VY = 73, VZ = Z0 + 5;             // victim chest (inside grief)
const SX = X0 + 2, SY = 80, SZ = Z0 + 2;             // survivor chest (same chunk, above)

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
async function chQuery(sql) {
    const r = await fetch(`${CH}/?query=${encodeURIComponent(sql)}`);
    return (await r.text()).trim();
}
function waitChat(bot, re, timeout) {
    return new Promise(resolve => {
        const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); resolve(m); } };
        bot.on('messagestr', h);
        setTimeout(() => { bot.removeListener('messagestr', h); resolve(null); }, timeout);
    });
}

const BOT = 'salv' + Date.now().toString(36).slice(-4);
const results = [];
const check = (name, ok, detail) => { results.push({ name, ok }); log(`  ${ok ? 'PASS' : 'FAIL'} — ${name}${detail ? ' :: ' + detail : ''}`); };

(async () => {
    log(`Salvage proof. grief (${X0},${GY0},${Z0})..(${X1},${GY1},${Z1}); victim @${VX},${VY},${VZ}; survivor @${SX},${SY},${SZ}; bot=${BOT}`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`);
    await sleep(1500);

    log('Stage 0: stone baseline over the grief box (RCON, unlogged)…');
    await rcon(`fill ${X0} ${GY0} ${Z0} ${X1} ${GY1} ${Z1} stone`);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`);
    await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${VX} ${SY + 3} ${VZ}`);
    await sleep(2500);
    try { await bot.waitForChunksToLoad(); } catch { }
    await sleep(1500);

    log('Stage 1: bot //set air over the grief box (LOGGED)…');
    bot.chat('//sel cuboid'); await sleep(150);
    bot.chat(`//pos1 ${X0},${GY0},${Z0}`); await sleep(150);
    bot.chat(`//pos2 ${X1},${GY1},${Z1}`); await sleep(150);
    const weDone = waitChat(bot, /(blocks? have been changed|operation completed|affected)/i, 60000);
    bot.chat('//set air');
    await weDone;
    await sleep(6000); // let the recorder drain to ClickHouse

    log('Stage 2: place VICTIM (diamonds, in grief) + SURVIVOR (emeralds, above)…');
    await rcon(`setblock ${VX} ${VY} ${VZ} chest`);
    await rcon(`data merge block ${VX} ${VY} ${VZ} {Items:[{Slot:0b,id:"minecraft:diamond",count:64},{Slot:1b,id:"minecraft:diamond",count:32}]}`);
    await rcon(`setblock ${SX} ${SY} ${SZ} chest`);
    await rcon(`data merge block ${SX} ${SY} ${SZ} {Items:[{Slot:0b,id:"minecraft:emerald",count:64}]}`);
    await sleep(1000);

    log('Stage 3: /sg rollback p:' + BOT + ' -g (restores stone, destroys victim chest)…');
    let summary = null;
    const h = m => { if (/(reversals|No results|rolled back|applied)/i.test(m)) summary = m; };
    bot.on('messagestr', h);
    bot.chat(`/sg rollback p:${BOT} t:1h -g`);
    const t0 = Date.now();
    while (summary == null && Date.now() - t0 < 60000) await sleep(200);
    bot.removeListener('messagestr', h);
    log(`  rollback: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(none/timeout)'}`);
    await sleep(6000); // let salvage persist + recorder drain

    log('── Verifying ──');
    // 1. CAPTURE + 2. PRECISION (ClickHouse salvage table)
    const rowCount = await chQuery(`SELECT count() FROM spyglass.spyglass_salvage FINAL WHERE deleted=0 AND operator_name='${BOT}'`);
    check('CAPTURE: exactly one salvage row (the victim)', rowCount === '1', `rows=${rowCount} (expect 1)`);
    const types = await chQuery(`SELECT container_type FROM spyglass.spyglass_salvage FINAL WHERE deleted=0 AND operator_name='${BOT}'`);
    check('CAPTURE: salvaged a CHEST', /CHEST/i.test(types), `container_type=${types}`);

    // 3. WORLD: victim restored to stone, survivor intact
    const vBlock = bot.blockAt(vec3(VX, VY, VZ))?.name;
    const sBlock = bot.blockAt(vec3(SX, SY, SZ))?.name;
    check('WORLD: victim cell restored to stone', vBlock === 'stone', `victim block=${vBlock}`);
    check('WORLD: survivor chest intact', sBlock === 'chest', `survivor block=${sBlock}`);

    // 4. LISTING: /sg inventory text shows diamonds, not emeralds
    const listing = (await rcon('sg inventory')).replace(/§./g, '');
    check('LISTING: /sg inventory lists this rollback', listing.includes('by ' + BOT) && /DIAMOND/i.test(listing),
        listing.replace(/\s+/g, ' ').slice(0, 200));
    // Survivor chest sat in the SAME chunk but outside the grief; precision =
    // exactly one container salvaged for this operator (the victim), not two.
    check('PRECISION: same-chunk survivor not salvaged', rowCount === '1', `bot salvaged ${rowCount} (survivor excluded)`);

    // 5. EXTRACT: player opens the GUI and takes the diamonds
    log('Stage 4: extract via the GUI (rollbacks -> chests -> items)…');
    let extracted = false;
    const waitWin = ms => Promise.race([new Promise(r => bot.once('windowOpen', r)), sleep(ms).then(() => null)]);
    try {
        bot.chat('/sg inventory');
        if (await waitWin(8000)) {                        // rollbacks level
            await sleep(700); bot.clickWindow(0, 0, 0);   // open the rollback
            if (await waitWin(8000)) {                    // chests level
                await sleep(700); bot.clickWindow(0, 0, 0); // open the container
                if (await waitWin(8000)) {                // items level
                    await sleep(700);
                    // each take re-renders the window; click slot 0 to drain
                    for (let i = 0; i < 4; i++) { try { bot.clickWindow(0, 0, 0); } catch { } await sleep(900); }
                }
            }
        }
        try { bot.closeWindow(bot.currentWindow); } catch { }
        await sleep(1500);
        const diamondsHeld = bot.inventory.items().filter(i => /diamond/.test(i.name)).reduce((s, i) => s + i.count, 0);
        extracted = diamondsHeld > 0;
        check('EXTRACT: diamonds moved into player inventory', extracted, `held=${diamondsHeld}`);
    } catch (e) {
        check('EXTRACT: diamonds moved into player inventory', false, 'gui error: ' + (e?.message || e));
    }
    await sleep(4000); // let withdraw + store update persist

    const afterCount = await chQuery(`SELECT count() FROM spyglass.spyglass_salvage FINAL WHERE deleted=0 AND operator_name='${BOT}'`);
    check('EXTRACT: snapshot emptied/removed after taking all', afterCount === '0', `rows=${afterCount} (expect 0)`);
    const withdraws = await chQuery(`SELECT count() FROM spyglass.event_records WHERE event='salvage-withdraw' AND source_player_name='${BOT}'`);
    check('EXTRACT: salvage-withdraw event logged', Number(withdraws) >= 1, `count=${withdraws}`);

    // Summary
    const passed = results.filter(r => r.ok).length;
    log(`\n──────── SALVAGE SUITE: ${passed}/${results.length} checks passed ────────`);
    for (const r of results) log(`  ${r.ok ? '✓' : '✗'} ${r.name}`);
    const allPass = results.every(r => r.ok);
    log(`\n  VERDICT: ${allPass ? 'PASS — container salvage works end-to-end.' : 'FAIL — see checks above.'}`);

    bot.quit();
    await sleep(1000);
    await rcon(`fill ${X0} ${GY0} ${Z0} ${X1} ${SY} ${Z1} air`);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(allPass ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
