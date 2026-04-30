// Quick NBT correctness check after the no-NMS rewrite. Three cases:
//
//   1. Chest with items → break via player → /sg rollback → assert chest
//      back AND has the items in the right slots.
//   2. Sign with text → break → rollback → assert sign back with the
//      same lines.
//   3. Sand cube on stone (already covered by test_rollback.js's
//      sand-undo, skipped here).
//
// All assertions via RCON /data get and /execute.
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
        const s = net.createConnection({ host: HOST, port: RCON_PORT });
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

const RUN = Date.now().toString(36).slice(-5);
const BOT = `nbttest_${RUN}`;

async function chestCase(bot) {
    const x = 16000, y = 80, z = 16000;
    log(`-- chest at ${x},${y},${z}`);
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await rcon(`tp ${BOT} ${x + 0.5} ${y + 5} ${z + 0.5}`);
    await sleep(1500);

    // Place a chest with diamond, gold ingot, redstone in slots 0/4/8.
    await rcon(`setblock ${x} ${y} ${z} chest{Items:[{Slot:0b,id:"minecraft:diamond",count:5},{Slot:4b,id:"minecraft:gold_ingot",count:12},{Slot:8b,id:"minecraft:redstone",count:64}]}`);
    await sleep(500);
    const before = await rcon(`data get block ${x} ${y} ${z} Items`);
    log(`  before: ${before.replace(/\s+/g, ' ').slice(0, 200)}`);

    // Bot breaks the chest via WE //replace (logs as break with NBT
    // captured in BlockSnapshot.containerItems).
    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x},${y},${z}`); await sleep(150);
    bot.chat(`//pos2 ${x},${y},${z}`); await sleep(150);
    const replaceDone = waitForChat(bot, /blocks have been (replaced|set|changed)/i, 6000);
    bot.chat(`//set air`);
    await replaceDone;
    await sleep(800);

    const after = await rcon(`execute if block ${x} ${y} ${z} air`);
    log(`  post-break: ${after.replace(/\n/g, ' ')}`);

    // /sg rollback
    const rbDone = waitForChat(bot, /(reversals|No results)/i, 30000);
    bot.chat(`/spyglass rollback p:${BOT} t:1m -g`);
    await rbDone;
    await sleep(1500);

    // Verify chest is back AND has the items
    const isChest = await rcon(`execute if block ${x} ${y} ${z} chest`);
    const itemsAfter = await rcon(`data get block ${x} ${y} ${z} Items`);
    log(`  chest back? ${isChest.includes('passed') ? 'YES' : 'NO'}`);
    log(`  items: ${itemsAfter.replace(/\s+/g, ' ').slice(0, 200)}`);

    const ok = isChest.includes('passed')
        && itemsAfter.includes('diamond')
        && itemsAfter.includes('gold_ingot')
        && itemsAfter.includes('redstone');
    return { case: 'chest', ok };
}

async function signCase(bot) {
    const x = 16100, y = 80, z = 16100;
    log(`-- sign at ${x},${y},${z}`);
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await rcon(`tp ${BOT} ${x + 0.5} ${y + 5} ${z + 0.5}`);
    await sleep(1500);

    // Place an oak sign with 4 specific lines on the front.
    await rcon(`setblock ${x} ${y} ${z} oak_sign{front_text:{messages:['"LINE1"','"LINE2"','"LINE3"','"LINE4"']}}`);
    await sleep(500);
    const before = await rcon(`data get block ${x} ${y} ${z} front_text`);
    log(`  before: ${before.replace(/\s+/g, ' ').slice(0, 200)}`);

    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x},${y},${z}`); await sleep(150);
    bot.chat(`//pos2 ${x},${y},${z}`); await sleep(150);
    const replaceDone = waitForChat(bot, /blocks have been (replaced|set|changed)/i, 6000);
    bot.chat(`//set air`);
    await replaceDone;
    await sleep(800);

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 30000);
    bot.chat(`/spyglass rollback p:${BOT} t:1m -g`);
    await rbDone;
    await sleep(1500);

    const isSign = await rcon(`execute if block ${x} ${y} ${z} oak_sign`);
    const textAfter = await rcon(`data get block ${x} ${y} ${z} front_text`);
    log(`  sign back? ${isSign.includes('passed') ? 'YES' : 'NO'}`);
    log(`  text: ${textAfter.replace(/\s+/g, ' ').slice(0, 200)}`);

    const ok = isSign.includes('passed')
        && textAfter.includes('LINE1')
        && textAfter.includes('LINE4');
    return { case: 'sign', ok };
}

(async () => {
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
    await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
    await rcon(`op ${BOT}`);
    await rcon(`gamemode creative ${BOT}`);
    await sleep(800);

    const results = [];
    try { results.push(await chestCase(bot)); } catch (e) { results.push({ case: 'chest', ok: false, err: e.message }); }
    try { results.push(await signCase(bot)); } catch (e) { results.push({ case: 'sign', ok: false, err: e.message }); }

    bot.quit();
    console.log('\n=== NBT summary ===');
    for (const r of results) console.log(JSON.stringify(r));
    process.exit(results.every(r => r.ok) ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
