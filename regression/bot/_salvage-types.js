// Which block types does salvage capture? Places a row of different containers
// (+ an enchanting table, which holds no items) inside a griefed region, rolls
// back, and reports which types landed in the salvage store.
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const CH = 'http://localhost:8123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);

const X0 = 29000, Z0 = 29000, GY0 = 72, GY1 = 74, X1 = X0 + 9, Z1 = Z0 + 2;
const Y = 73, Z = Z0; // container row

// type -> NBT Items for /data merge ('' = no items, e.g. enchanting_table)
const PLACE = [
    ['furnace', `{Items:[{Slot:0b,id:"minecraft:iron_ore",count:8},{Slot:1b,id:"minecraft:coal",count:16}]}`],
    ['trapped_chest', `{Items:[{Slot:0b,id:"minecraft:gold_ingot",count:32}]}`],
    ['barrel', `{Items:[{Slot:0b,id:"minecraft:emerald",count:16}]}`],
    ['shulker_box', `{Items:[{Slot:0b,id:"minecraft:redstone",count:48}]}`],
    ['brewing_stand', `{Items:[{Slot:4b,id:"minecraft:blaze_powder",count:4}]}`],
    ['dispenser', `{Items:[{Slot:0b,id:"minecraft:arrow",count:64}]}`],
    ['hopper', `{Items:[{Slot:0b,id:"minecraft:wheat",count:12}]}`],
    ['enchanting_table', ''], // holds no items — must NOT be salvaged
];

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
const BOT = 'styp' + Date.now().toString(36).slice(-4);

(async () => {
    log(`container-type salvage test; bot=${BOT}`);
    await rcon(`forceload add ${X0} ${Z0} ${X1} ${Z1}`); await sleep(1500);
    await rcon(`fill ${X0} ${GY0} ${Z0} ${X1} ${GY1} ${Z1} stone`);

    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
    await rcon(`tp ${BOT} ${X0} ${GY1 + 6} ${Z0}`); await sleep(2500);
    try { await bot.waitForChunksToLoad(); } catch { } await sleep(1000);

    // grief the region (logged)
    bot.chat('//sel cuboid'); await sleep(120);
    bot.chat(`//pos1 ${X0},${GY0},${Z0}`); await sleep(120);
    bot.chat(`//pos2 ${X1},${GY1},${Z1}`); await sleep(120);
    bot.chat('//set air'); await sleep(5000);

    // place the containers in the now-air region
    log('placing: ' + PLACE.map(p => p[0]).join(', '));
    for (let i = 0; i < PLACE.length; i++) {
        const [type, items] = PLACE[i]; const x = X0 + 1 + i;
        await rcon(`setblock ${x} ${Y} ${Z} ${type}`);
        if (items) await rcon(`data merge block ${x} ${Y} ${Z} ${items}`);
    }
    await sleep(1000);

    // roll the grief back -> stone overwrites every container
    bot.chat(`/sg rollback p:${BOT} t:1h -g`); await sleep(7000);

    const rows = await ch(`SELECT container_type, length(items) AS bytes FROM spyglass.spyglass_salvage FINAL WHERE deleted=0 AND operator_name='${BOT}' ORDER BY container_type FORMAT TSV`);
    const salvaged = new Set(rows.split('\n').filter(Boolean).map(r => r.split('\t')[0].toLowerCase()));
    log('\n──────── container types salvaged ────────');
    for (const [type] of PLACE) {
        const expectCapture = type !== 'enchanting_table';
        const got = salvaged.has(type);
        const ok = got === expectCapture;
        log(`  ${ok ? 'PASS' : 'FAIL'} — ${type.padEnd(18)} ${got ? 'salvaged' : 'not salvaged'}${expectCapture ? '' : ' (expected: holds no items)'}`);
    }
    log('\nraw salvage rows:\n' + rows);

    bot.quit(); await sleep(800);
    await rcon(`fill ${X0} ${GY0} ${Z0} ${X1} ${GY1} ${Z1} air`);
    await rcon(`forceload remove ${X0} ${Z0} ${X1} ${Z1}`);
    process.exit(0);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
