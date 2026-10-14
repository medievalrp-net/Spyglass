// #98 live proof: container deposit burst -> IMMEDIATE rollback (read-your-writes).
//
// ContainerTransactionListener now serializes deposits OFF the main thread
// (#98). A rollback flushes the recorder for read-your-writes, and that flush
// must drain the deferred serialization stage FIRST — otherwise a rollback
// issued right after a deposit burst would miss records still mid-serialization
// and only PARTIALLY revert the chest.
//
// A real bot burst-deposits distinct stacks into a chest, then rolls back with
// NO drain delay. If the flush barrier holds, EVERY deposit is reverted (chest
// empty); a broken barrier leaves some stacks behind.
//
// Built at the bot's spawn chunk (already loaded) with vanilla minecraft:
// commands (the RP server's plugins shadow /tp, /fill, etc.). Ports are
// env-overridable; defaults target the standard ../RP_Server. Verified for
// #98 against an isolated RP_Server_2 via:
//   SG_PORT=25567 SG_RCON_PORT=25577 node scenario-98.js
import mineflayer from 'mineflayer';
import vec3 from 'vec3';
import net from 'net';

const HOST = process.env.SG_HOST || '127.0.0.1';
const PORT = Number(process.env.SG_PORT || 25566);
const RCON_PORT = Number(process.env.SG_RCON_PORT || 25576);
const PASS = process.env.SG_RCON_PASS || 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...a) => console.log(ts(), ...a);
const ITEMS = ['diamond', 'emerald', 'gold_ingot', 'iron_ingot', 'redstone',
    'lapis_lazuli', 'coal', 'copper_ingot', 'quartz'];

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

const BOT = 'sg98' + Date.now().toString(36).slice(-4);
const results = [];
const check = (name, ok, detail) => { results.push({ name, ok }); log(`  ${ok ? 'PASS' : 'FAIL'} — ${name}${detail ? ' :: ' + detail : ''}`); };

let bot, CHX, CHY, CHZ;
async function waitForBlock(x, y, z, want, timeoutMs) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
        const b = bot.blockAt(vec3(x, y, z));
        if (b && (!want || b.name === want)) return b;
        await sleep(500);
    }
    return bot.blockAt(vec3(x, y, z));
}
async function chestItemCount() {
    const block = await waitForBlock(CHX, CHY, CHZ, 'chest', 15000);
    const win = await bot.openContainer(block);
    const n = win.containerItems().reduce((s, i) => s + i.count, 0);
    win.close();
    await sleep(400);
    return n;
}

(async () => {
    log(`#98 deposit->rollback proof. bot=${BOT}`);
    bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    bot.on('kicked', r => { log('KICKED', JSON.stringify(r)); });
    await sleep(500);

    const p = bot.entity.position;
    const bx = Math.floor(p.x), by = Math.floor(p.y), bz = Math.floor(p.z);
    CHX = bx + 1; CHY = by; CHZ = bz; // chest adjacent, same (loaded) chunk

    await rcon(`op ${BOT}`);
    await rcon(`minecraft:gamemode survival ${BOT}`);
    await rcon(`minecraft:fill ${bx - 1} ${by - 1} ${bz - 1} ${bx + 2} ${by - 1} ${bz + 2} stone`);
    await rcon(`minecraft:setblock ${CHX} ${CHY} ${CHZ} air`);
    await rcon(`minecraft:setblock ${CHX} ${CHY} ${CHZ} chest`);
    await rcon(`minecraft:teleport ${BOT} ${bx + 0.5} ${by} ${bz + 0.5}`);
    await sleep(2500);

    const chestBlock = await waitForBlock(CHX, CHY, CHZ, 'chest', 20000);
    check('SETUP: chest visible to bot', chestBlock?.name === 'chest', `block=${chestBlock?.name} @${CHX},${CHY},${CHZ}`);
    if (chestBlock?.name !== 'chest') throw new Error('chest not loaded in bot view');

    for (const it of ITEMS) await rcon(`minecraft:give ${BOT} minecraft:${it} 8`);
    await sleep(1500);

    log('Stage: open chest, burst-deposit all stacks (serialized off-thread)…');
    const chest = await bot.openContainer(chestBlock);
    let deposited = 0;
    for (const it of ITEMS) {
        const id = bot.registry.itemsByName[it]?.id;
        if (id == null) continue;
        try { await chest.deposit(id, null, 8); deposited++; } catch (e) { log('  deposit fail', it, e?.message); }
    }
    chest.close();
    await sleep(300);
    log(`  deposited ${deposited}/${ITEMS.length} stacks`);
    check('SETUP: deposits actually happened', deposited > 0, `deposited=${deposited}`);

    // IMMEDIATE rollback — no drain delay. flush() must drain the deferred
    // serialization stage before reading, or this only partially reverts.
    log('Stage: IMMEDIATE /sg rollback (no drain delay) — the read-your-writes test…');
    let summary = null;
    const h = m => { if (/(revers|rolled back|applied|No results|affected|change)/i.test(m)) summary = m; };
    bot.on('messagestr', h);
    bot.chat(`/sg rollback p:${BOT} t:1h -g`);
    const t0 = Date.now();
    while (summary == null && Date.now() - t0 < 90000) await sleep(200);
    bot.removeListener('messagestr', h);
    log(`  rollback: ${summary ? summary.replace(/\s+/g, ' ').trim() : '(none/timeout)'}`);
    check('ROLLBACK: found deposits to revert (not "No results")',
        summary != null && !/No results/i.test(summary), `summary=${summary ? summary.replace(/\s+/g, ' ').trim().slice(0, 120) : '(none)'}`);
    await sleep(4000); // let the world-write pool apply

    const left = await chestItemCount();
    check('READ-YOUR-WRITES: chest fully reverted after immediate rollback',
        left === 0, `items left=${left} (deposited ${deposited} stacks; >0 means flush missed in-flight records)`);

    const passed = results.filter(r => r.ok).length;
    log(`\n──────── #98 SUITE: ${passed}/${results.length} checks passed ────────`);
    for (const r of results) log(`  ${r.ok ? '✓' : '✗'} ${r.name}`);
    const allPass = results.every(r => r.ok);
    log(`\n  VERDICT: ${allPass ? 'PASS — deferred container serialization holds the rollback flush contract.' : 'FAIL — see checks.'}`);

    bot.quit();
    await sleep(1000);
    await rcon(`minecraft:setblock ${CHX} ${CHY} ${CHZ} air`);
    process.exit(allPass ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e); try { bot?.quit(); } catch { } process.exit(2); });
