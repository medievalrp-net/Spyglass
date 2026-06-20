// Spyglass rollback stress + edge-case suite.
//
// Goal: find what breaks. Each test case sets up a known scenario,
// triggers a rollback, and verifies the post-state matches expectation.
// Anything that drifts, throws, or hits a fallback warning is reported
// as a finding.
//
// Categories:
//   1. NBT-heavy containers (chests with named/enchanted/lored items)
//   2. Signs (text both sides)
//   3. Banners (patterns)
//   4. Jukeboxes (records)
//   5. Multi-block tile entities (decorated pots, lecterns, brewing stand)
//   6. Connected blocks (bed, door, tall flower) — both halves
//   7. Falling blocks at scale (sand columns, gravel, concrete powder)
//   8. Light sources (torches, glowstone — light propagation)
//   9. Multi-piece (banner+pattern, chest+chest pair)
//  10. Scale tiers: 10k, 100k, 500k, 1M
//  11. Mixed-density rollback (chests + signs + sand mixed in one cube)
//  12. Repeated rapid rollbacks (race conditions)
//
// Each test reports {id, ok, details}. The runner prints a structured
// findings table at the end.

import mineflayer from 'mineflayer';
import net from 'net';
import fs from 'fs';
import vec3pkg from 'vec3';
const Vec3 = vec3pkg.Vec3 || vec3pkg.default || vec3pkg;

const HOST = '127.0.0.1';
const PORT = 25566;
const RCON_PORT = 25576;
const RCON_PASS = 'test123';
const BOT_NAME = 'rolltest';
const SERVER_LOG = (process.env.RP_SERVER || new URL('../../../RP_Server', import.meta.url).pathname) + '/logs/latest.log';
const CH_URL = 'http://localhost:8123/';

// --- minimal RCON client ---
function rcon(cmd) {
    return new Promise((resolve, reject) => {
        const s = net.createConnection(RCON_PORT, HOST);
        let stage = 0;
        const requestId = 0x1337;
        const buffers = [];
        s.on('error', reject);
        s.on('connect', () => s.write(packet(requestId, 3, RCON_PASS)));
        s.on('data', chunk => {
            buffers.push(chunk);
            const all = Buffer.concat(buffers);
            if (all.length < 4) return;
            const len = all.readInt32LE(0);
            if (all.length < len + 4) return;
            const id = all.readInt32LE(4);
            const body = all.slice(12, 12 + len - 10).toString('utf8');
            if (stage === 0) {
                if (id === -1) return reject(new Error('RCON auth failed'));
                stage = 1;
                buffers.length = 0;
                s.write(packet(requestId, 2, cmd));
            } else {
                s.end();
                resolve(body);
            }
        });
    });
}
function packet(id, type, body) {
    const bodyBuf = Buffer.from(body, 'utf8');
    const len = 4 + 4 + bodyBuf.length + 2;
    const out = Buffer.alloc(4 + len);
    out.writeInt32LE(len, 0);
    out.writeInt32LE(id, 4);
    out.writeInt32LE(type, 8);
    bodyBuf.copy(out, 12);
    out.writeInt16LE(0, 12 + bodyBuf.length);
    return out;
}

async function chQuery(sql) {
    const r = await fetch(CH_URL, { method: 'POST', body: sql });
    return (await r.text()).trim();
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '[' + new Date().toISOString().slice(11, 19) + ']';
const log = (...args) => console.log(ts(), ...args);

function getLogTailMarker() {
    try { return fs.readFileSync(SERVER_LOG, 'utf8').slice(-200); }
    catch { return null; }
}
function checkFallbackWarnings(sinceMarker) {
    const text = fs.readFileSync(SERVER_LOG, 'utf8');
    const tail = sinceMarker ? text.slice(text.indexOf(sinceMarker) + sinceMarker.length) : text;
    return tail.split('\n').filter(l =>
        l.includes('ChunkDirectWriter unavailable') ||
        l.includes('ChunkDirectWriter failed') ||
        l.includes('ChunkResender unavailable') ||
        l.includes('ChunkResender failed'));
}
function checkExceptions(sinceMarker) {
    const text = fs.readFileSync(SERVER_LOG, 'utf8');
    const tail = sinceMarker ? text.slice(text.indexOf(sinceMarker) + sinceMarker.length) : text;
    return tail.split('\n').filter(l =>
        /Spyglass.*(Exception|Error)/.test(l) ||
        /\[Spyglass\].*SEVERE/.test(l) ||
        /(at net\.medievalrp\.spyglass)/.test(l));
}

function waitForChat(bot, re, timeout) {
    return new Promise(resolve => {
        const handler = m => { if (re.test(m)) { bot.removeListener('messagestr', handler); resolve(m); } };
        bot.on('messagestr', handler);
        setTimeout(() => { bot.removeListener('messagestr', handler); resolve(null); }, timeout);
    });
}

// --- test cases ----------------------------------------------------------

// Case helpers
async function clearArea(x0, y0, z0, x1, y1, z1) {
    await rcon(`fill ${x0} ${y0} ${z0} ${x1} ${y1} ${z1} air`);
    await sleep(150);
}
async function tpBot(x, y, z) {
    await rcon(`tp ${BOT_NAME} ${x} ${y} ${z}`);
    await sleep(800);
}
async function probe(x, y, z, expected) {
    const r = await rcon(`execute if block ${x} ${y} ${z} minecraft:${expected}`);
    return r.toLowerCase().includes('passed');
}
async function getBlockData(x, y, z) {
    return await rcon(`data get block ${x} ${y} ${z}`);
}
// Returns true if the block at (x,y,z) has the NBT path. Use this
// instead of getBlockData for verification — RCON truncates /data get
// responses on Paper around ~120 chars, but `execute if data` returns
// just "Test passed" or "Test failed" regardless of NBT size.
async function hasData(x, y, z, path) {
    const r = await rcon(`execute if data block ${x} ${y} ${z} ${path}`);
    return r.toLowerCase().includes('passed');
}

// Helper: bot uses WE //replace to break a single cell (or small box).
// Picks up Spyglass via the LoggingExtent, captures full BlockSnapshot
// including tile-entity NBT.
async function botReplaceCell(bot, x, y, z, target = 'air') {
    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x},${y},${z}`); await sleep(150);
    bot.chat(`//pos2 ${x},${y},${z}`); await sleep(150);
    const done = waitForChat(bot, /blocks have been (changed|replaced|set)|operation completed/i, 8000);
    bot.chat(`//set ${target}`);
    await done;
    await sleep(400);
}
async function botReplaceBox(bot, x0, y0, z0, x1, y1, z1, target = 'air') {
    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(150);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(150);
    const done = waitForChat(bot, /blocks have been (changed|replaced|set)|operation completed/i, 30000);
    bot.chat(`//set ${target}`);
    await done;
    await sleep(400);
}
// Robust-ish NBT checks. The /data get block dump uses Mojang's NBT
// printer which has subtly different formatting across Paper builds
// (Slot:0b vs Slot: 0b, spaces vs no spaces). Count distinguishing
// tokens loosely.
function nbtItemCount(dataDump) {
    // Best signal: count 'id: "minecraft:<not chest>"' occurrences inside
    // the Items section. Each item has an id; the outer block also has
    // one ("minecraft:chest") but it appears before Items, so we slice.
    const idx = dataDump.indexOf('Items:');
    if (idx < 0) return 0;
    const tail = dataDump.slice(idx);
    return (tail.match(/id:\s*"minecraft:/g) || []).length;
}
function nbtHasNonEmptyMessages(dataDump, side = 'front_text') {
    const idx = dataDump.indexOf(side + ':');
    if (idx < 0) return false;
    const tail = dataDump.slice(idx, idx + 1500);
    return /messages:[\s\S]*?"[^"\\]+"/.test(tail);
}
function nbtHasPatterns(dataDump) {
    return /pattern:\s*"minecraft:/.test(dataDump);
}

// 1. Chest with NBT items — uses /item replace (1.21+ component syntax)
// because /setblock NBT for Items components doesn't apply enchantment
// components on Paper 1.21.8.
async function testNbtChest(bot) {
    const id = 'nbt-chest';
    const x = 1500, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);

    // Empty chest first, then populate slots with `/item replace block`
    // which uses the modern component syntax that actually applies.
    await rcon(`setblock ${x} ${y} ${z} chest`);
    await sleep(200);
    await rcon(`item replace block ${x} ${y} ${z} container.0 with minecraft:diamond_sword[minecraft:custom_name="Excalibur",minecraft:enchantments={"minecraft:sharpness":5,"minecraft:unbreaking":3}]`);
    await rcon(`item replace block ${x} ${y} ${z} container.1 with minecraft:enchanted_book[minecraft:stored_enchantments={"minecraft:mending":1}] 32`);
    await rcon(`item replace block ${x} ${y} ${z} container.5 with minecraft:potion[minecraft:potion_contents={potion:"minecraft:strong_swiftness"}]`);
    await sleep(300);

    const setup0 = await hasData(x, y, z, 'Items[0].id');
    const setup1 = await hasData(x, y, z, 'Items[1].id');
    const setup2 = await hasData(x, y, z, 'Items[2].id');
    const setupSwordEnch = await hasData(x, y, z, 'Items[0].components."minecraft:enchantments"');
    const setupBookEnch = await hasData(x, y, z, 'Items[1].components."minecraft:stored_enchantments"');
    const setupCustomName = await hasData(x, y, z, 'Items[0].components."minecraft:custom_name"');
    if (!(setup0 && setup1 && setup2 && setupSwordEnch && setupBookEnch && setupCustomName)) {
        return { id, ok: false, reason: `setup incomplete`, setup: { setup0, setup1, setup2, setupSwordEnch, setupBookEnch, setupCustomName } };
    }

    // Bot //sets cell to air via WE — LoggingExtent captures snapshot
    await botReplaceCell(bot, x, y, z, 'air');
    if (!await probe(x, y, z, 'air')) {
        return { id, ok: false, reason: 'WE //set air did not clear' };
    }

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 30000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1500);

    const hasChest = await probe(x, y, z, 'chest');
    const after0 = await hasData(x, y, z, 'Items[0]');
    const after1 = await hasData(x, y, z, 'Items[1]');
    const after2 = await hasData(x, y, z, 'Items[2]');
    // Verify item identity is preserved (not just count)
    const swordOk = await rcon(`execute if data block ${x} ${y} ${z} {Items:[{Slot:0b,id:"minecraft:diamond_sword"}]}`);
    const bookOk = await rcon(`execute if data block ${x} ${y} ${z} {Items:[{Slot:1b,id:"minecraft:enchanted_book"}]}`);
    const potionOk = await rcon(`execute if data block ${x} ${y} ${z} {Items:[{Slot:5b,id:"minecraft:potion"}]}`);
    // Verify NBT (custom_name + enchantments + potion contents)
    const customName = await hasData(x, y, z, 'Items[0].components."minecraft:custom_name"');
    const sharpness = await hasData(x, y, z, 'Items[0].components."minecraft:enchantments"');
    const mending = await hasData(x, y, z, 'Items[1].components."minecraft:stored_enchantments"');
    const potionNBT = await hasData(x, y, z, 'Items[2].components."minecraft:potion_contents"');

    const ok = hasChest && after0 && after1 && after2
        && swordOk.includes('passed') && bookOk.includes('passed') && potionOk.includes('passed')
        && customName && sharpness && mending && potionNBT;
    return {
        id, ok, hasChest,
        items: { after0, after1, after2 },
        identity: { sword: swordOk.includes('passed'), book: bookOk.includes('passed'), potion: potionOk.includes('passed') },
        nbt: { customName, sharpness, mending, potionNBT }
    };
}

// 2. Sign with text
async function testSign(bot) {
    const id = 'sign';
    const x = 1502, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x, y, z, x, y, z);
    await rcon(`setblock ${x} ${y} ${z} oak_sign{front_text:{messages:['"Hello"','"World"','"Line3"','"Line4"']},back_text:{messages:['"Back1"','"Back2"','"Back3"','"Back4"']}}`);
    await sleep(400);
    if (!await hasData(x, y, z, 'front_text.messages[0]')) {
        return { id, ok: false, reason: 'setup failed' };
    }
    await botReplaceCell(bot, x, y, z, 'air');
    if (!await probe(x, y, z, 'air')) return { id, ok: false, reason: '//set air failed' };

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1000);

    const hasSign = await probe(x, y, z, 'oak_sign');
    // Check that BOTH sides have non-empty content. The exact message
    // format varies (chat component vs raw string) so just verify the
    // path exists and one specific message survived.
    const front0 = await rcon(`execute if data block ${x} ${y} ${z} {front_text:{messages:['"Hello"']}}`);
    const back0 = await rcon(`execute if data block ${x} ${y} ${z} {back_text:{messages:['"Back1"']}}`);
    const frontOk = front0.includes('passed');
    const backOk = back0.includes('passed');
    return { id, ok: hasSign && frontOk && backOk, hasSign, frontOk, backOk };
}

// 3. Banner with patterns
async function testBanner(bot) {
    const id = 'banner';
    const x = 1504, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x, y, z, x, y, z);
    await rcon(`setblock ${x} ${y} ${z} red_banner{patterns:[{pattern:"minecraft:cross",color:"white"},{pattern:"minecraft:stripe_top",color:"black"}]}`);
    await sleep(400);
    if (!await hasData(x, y, z, 'patterns[0]')) {
        return { id, ok: false, reason: 'setup failed' };
    }
    await botReplaceCell(bot, x, y, z, 'air');

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1000);

    const hasBanner = await probe(x, y, z, 'red_banner');
    const p0 = await hasData(x, y, z, 'patterns[0]');
    const p1 = await hasData(x, y, z, 'patterns[1]');
    const crossOk = (await rcon(`execute if data block ${x} ${y} ${z} {patterns:[{pattern:"minecraft:cross"}]}`)).includes('passed');
    return { id, ok: hasBanner && p0 && p1 && crossOk, hasBanner, p0, p1, crossOk };
}

// 4. Jukebox with disc — KNOWN BUG: rollback throws NPE on this.level
async function testJukebox(bot) {
    const id = 'jukebox';
    const x = 1506, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x, y, z, x, y, z);
    await rcon(`setblock ${x} ${y} ${z} jukebox{RecordItem:{id:"minecraft:music_disc_pigstep",count:1}}`);
    await sleep(400);
    if (!await hasData(x, y, z, 'RecordItem')) {
        return { id, ok: false, reason: 'setup failed' };
    }
    await botReplaceCell(bot, x, y, z, 'air');

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1000);

    const hasJukebox = await probe(x, y, z, 'jukebox');
    const hasRecord = await hasData(x, y, z, 'RecordItem');
    return { id, ok: hasJukebox && hasRecord, hasJukebox, hasRecord };
}

// 5. Decorated pot — sherds are stored in block components, not BlockEntity
async function testDecoratedPot(bot) {
    const id = 'decorated-pot';
    const x = 1508, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x, y, z, x, y, z);
    await rcon(`setblock ${x} ${y} ${z} decorated_pot{sherds:["minecraft:angler_pottery_sherd","minecraft:archer_pottery_sherd","minecraft:arms_up_pottery_sherd","minecraft:blade_pottery_sherd"]}`);
    await sleep(400);
    if (!await hasData(x, y, z, 'sherds[0]')) {
        return { id, ok: false, reason: 'setup failed' };
    }

    await botReplaceCell(bot, x, y, z, 'air');

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1000);

    const hasPot = await probe(x, y, z, 'decorated_pot');
    const s0 = await hasData(x, y, z, 'sherds[0]');
    const s1 = await hasData(x, y, z, 'sherds[1]');
    const s2 = await hasData(x, y, z, 'sherds[2]');
    const s3 = await hasData(x, y, z, 'sherds[3]');
    return { id, ok: hasPot && s0 && s1 && s2 && s3, hasPot, s0, s1, s2, s3 };
}

// 6. Bed (multi-block) — break via WE //set air over both halves' bounding box
async function testBed(bot) {
    const id = 'bed';
    const x = 1510, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x + 1} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x, y, z, x + 1, y, z);
    await rcon(`setblock ${x} ${y} ${z} red_bed[part=foot,facing=east]`);
    await rcon(`setblock ${x + 1} ${y} ${z} red_bed[part=head,facing=east]`);
    await sleep(400);
    if (!await probe(x, y, z, 'red_bed') || !await probe(x + 1, y, z, 'red_bed')) {
        return { id, ok: false, reason: 'setup failed' };
    }
    await botReplaceBox(bot, x, y, z, x + 1, y, z, 'air');

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1500);

    const fA = await probe(x, y, z, 'red_bed');
    const fB = await probe(x + 1, y, z, 'red_bed');
    return { id, ok: fA && fB, fA, fB };
}

// 7. Door (multi-block vertical)
async function testDoor(bot) {
    const id = 'door';
    const x = 1513, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x, y, z, x, y + 2, z);
    await rcon(`setblock ${x} ${y - 1} ${z} stone`);
    await rcon(`setblock ${x} ${y} ${z} oak_door[half=lower,hinge=left,facing=south]`);
    await rcon(`setblock ${x} ${y + 1} ${z} oak_door[half=upper,hinge=left,facing=south]`);
    await sleep(400);
    if (!await probe(x, y, z, 'oak_door') || !await probe(x, y + 1, z, 'oak_door')) {
        return { id, ok: false, reason: 'setup failed' };
    }
    await botReplaceBox(bot, x, y, z, x, y + 1, z, 'air');

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1500);

    const lo = await probe(x, y, z, 'oak_door');
    const hi = await probe(x, y + 1, z, 'oak_door');
    return { id, ok: lo && hi, lo, hi };
}

// 8. Sand-column undo (regression: sand must NOT fall on undo)
async function testSandColumnUndo(bot) {
    const id = 'sand-column-undo';
    const x = 1520, z = 1500;
    const yStone = 100;
    const ySandLo = 101, ySandHi = 116;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, ySandHi + 5, z + 0.5);
    await rcon(`fill ${x} ${yStone - 5} ${z} ${x} ${ySandHi + 5} ${z} air`);
    await rcon(`fill ${x} ${yStone} ${z} ${x} ${yStone} ${z} stone`);
    await rcon(`fill ${x} ${ySandLo} ${z} ${x} ${ySandHi} ${z} sand`);
    await sleep(500);

    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x},${ySandLo},${z}`); await sleep(150);
    bot.chat(`//pos2 ${x},${ySandHi},${z}`); await sleep(150);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 10000);
    bot.chat(`//replace sand air`);
    await replaceDone;
    await sleep(800);

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 30000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(800);

    const undoDone = waitForChat(bot, /(reversed|undone|reverted|reversals|No results)/i, 30000);
    bot.chat(`/spyglass undo`);
    await undoDone;
    await sleep(1500);

    let air = 0, sand = 0, other = 0;
    for (let y = ySandLo; y <= ySandHi; y++) {
        if (await probe(x, y, z, 'air')) { air++; continue; }
        if (await probe(x, y, z, 'sand')) { sand++; continue; }
        other++;
    }
    let belowSand = 0;
    for (let y = yStone - 1; y >= yStone - 5; y--) {
        if (await probe(x, y, z, 'sand')) belowSand++;
    }
    const ok = sand === 0 && belowSand === 0 && air === (ySandHi - ySandLo + 1);
    return { id, ok, air, sand, other, belowSand };
}

// 9. Mixed-density rollback: stone + chests + signs + sand
async function testMixedDensity(bot) {
    const id = 'mixed-density';
    const x0 = 1530, y0 = 100, z0 = 1500;
    const SIDE = 8;
    await rcon(`forceload add ${x0} ${z0} ${x0 + SIDE} ${z0 + SIDE}`);
    await tpBot(x0 + SIDE / 2, y0 + SIDE + 5, z0 + SIDE / 2);
    await rcon(`fill ${x0} ${y0} ${z0} ${x0 + SIDE - 1} ${y0 + SIDE - 1} ${z0 + SIDE - 1} air`);
    // Sprinkle of mixed contents
    await rcon(`fill ${x0} ${y0} ${z0} ${x0 + SIDE - 1} ${y0 + SIDE - 1} ${z0 + SIDE - 1} stone`);
    await rcon(`setblock ${x0} ${y0 + 1} ${z0} chest{Items:[{Slot:0,Count:64,id:"diamond"}]}`);
    await rcon(`setblock ${x0 + 2} ${y0 + 1} ${z0 + 2} oak_sign{front_text:{messages:['"Test"','"Sign"','""','""']}}`);
    await rcon(`setblock ${x0 + 4} ${y0 + 1} ${z0 + 4} red_banner{patterns:[{pattern:"cross",color:"white"}]}`);
    await rcon(`fill ${x0 + 1} ${y0 + 2} ${z0 + 1} ${x0 + 1} ${y0 + 5} ${z0 + 1} sand`);
    await sleep(500);

    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(150);
    bot.chat(`//pos2 ${x0 + SIDE - 1},${y0 + SIDE - 1},${z0 + SIDE - 1}`); await sleep(150);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 15000);
    bot.chat(`//set air`);
    await replaceDone;
    await sleep(800);

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 30000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:2m -g`);
    await rbDone;
    await sleep(2000);

    // Verify the special blocks are back (path-based; RCON-truncation safe)
    const chestBack = await probe(x0, y0 + 1, z0, 'chest');
    const signBack = await probe(x0 + 2, y0 + 1, z0 + 2, 'oak_sign');
    const bannerBack = await probe(x0 + 4, y0 + 1, z0 + 4, 'red_banner');
    const sandBack = await probe(x0 + 1, y0 + 2, z0 + 1, 'sand');
    const chestHasItem = await hasData(x0, y0 + 1, z0, 'Items[0]');
    const chestHasDiamond = (await rcon(
        `execute if data block ${x0} ${y0 + 1} ${z0} {Items:[{id:"minecraft:diamond"}]}`)).includes('passed');

    const ok = chestBack && signBack && bannerBack && sandBack && chestHasItem && chestHasDiamond;
    return { id, ok, chestBack, signBack, bannerBack, sandBack, chestHasItem, chestHasDiamond };
}

// 10. Scale tier
async function testScale(bot, blocks, baseY) {
    const id = `scale-${blocks}`;
    const SIDE = Math.ceil(Math.cbrt(blocks));
    const x0 = 2000, z0 = 2000;
    const x1 = x0 + SIDE - 1, y0 = baseY, y1 = baseY + SIDE - 1, z1 = z0 + SIDE - 1;
    const expected = SIDE * SIDE * SIDE;
    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await tpBot(x0 + 0.5, y1 + 5, z0 + 0.5);
    // Slab-fill stone (cap on /fill is 32768)
    const FILL_CAP = 32768;
    const cellsPerSlab = Math.floor(FILL_CAP / (SIDE * SIDE));
    let yCursor = y0;
    while (yCursor <= y1) {
        const yEnd = Math.min(y1, yCursor + Math.max(1, cellsPerSlab) - 1);
        await rcon(`fill ${x0} ${yCursor} ${z0} ${x1} ${yEnd} ${z1} stone`);
        yCursor = yEnd + 1;
    }
    await sleep(500);

    bot.chat(`//sel cuboid`); await sleep(150);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(150);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(150);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, Math.max(60000, expected / 50));
    const burstT0 = Date.now();
    bot.chat(`//replace stone air`);
    await replaceDone;
    const burstMs = Date.now() - burstT0;
    // Settle: recorder needs to drain N events to ClickHouse before
    // /sg rollback queries. AsyncRecorder drains in batches of 10k at
    // ~50-100k/sec, so for very large bursts give it generous time.
    const drainSettleMs = Math.max(2000, Math.ceil(expected / 50));
    await sleep(drainSettleMs);

    const rbT0 = Date.now();
    const rbDone = waitForChat(bot, /(reversals|No results)/i, Math.max(120000, expected));
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:5m -g`);
    const rbResult = await rbDone;
    const rbMs = Date.now() - rbT0;
    await sleep(2000);

    // Sample 64 cells
    let stone = 0, scanned = 0;
    const stride = Math.max(1, Math.floor(expected / 64));
    for (let i = 0; i < expected && scanned < 64; i += stride) {
        const dx = i % SIDE;
        const rest = Math.floor(i / SIDE);
        const dy = rest % SIDE;
        const dz = Math.floor(rest / SIDE);
        const X = x0 + dx, Y = y0 + dy, Z = z0 + dz;
        if (await probe(X, Y, Z, 'stone')) stone++;
        scanned++;
    }
    const ok = stone === scanned;
    return { id, expected, ok, stone, scanned, burstMs, rbMs, summary: rbResult ? rbResult.slice(0, 120) : null };
}

// 11. Item frame entity — Spyglass logs entity damage/death events,
// rollback should re-spawn. Note: console-/kill is NOT player-attributed,
// so this test is more about whether Spyglass even captures the entity
// destroy in the first place. If it doesn't, that's a known limitation
// (entity rollback only works for player-attributed entity ops).
async function testItemFrame(bot) {
    const id = 'item-frame';
    const x = 1540, y = 100, z = 1500;
    await rcon(`forceload add ${x} ${z} ${x} ${z}`);
    await tpBot(x + 0.5, y + 5, z + 0.5);
    await clearArea(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    await rcon(`setblock ${x} ${y} ${z} stone`);
    await rcon(`summon item_frame ${x + 0.5} ${y + 1} ${z - 0.4} {Item:{id:"diamond",count:1},Facing:2}`);
    await sleep(500);

    // /kill is server-origin, no player attribution. Spyglass likely
    // won't have a player record to roll back. Document the result.
    await rcon(`kill @e[type=item_frame,distance=..3]`);
    await sleep(1500);

    const rbDone = waitForChat(bot, /(reversals|No results)/i, 20000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:1m -g`);
    await rbDone;
    await sleep(1500);

    // Count item frames near the location after rollback
    const findResult = await rcon(`execute as @e[type=item_frame,distance=..5] at @s run tp @s ~ ~ ~`);
    // /tp echoes "Teleported X to Y, Y, Y" once per matched entity
    const teleports = (findResult.match(/Teleported/g) || []).length;
    const ok = teleports >= 1;
    return { id, ok, teleports, note: 'kill via console — no player attribution; expected to fail unless Spyglass logs server-origin entity deaths' };
}

// --- runner --------------------------------------------------------------

async function run() {
    log('connecting bot...');
    const bot = mineflayer.createBot({
        host: HOST, port: PORT, username: BOT_NAME, version: '1.21.4',
    });
    await new Promise((resolve, reject) => {
        bot.once('spawn', resolve);
        bot.once('error', reject);
        bot.once('end', () => reject(new Error('bot disconnected before spawn')));
    });
    bot.on('messagestr', m => {
        if (/(error|fail|exception|skip|reason)/i.test(m)) {
            log('  chat>', m.replace(/\s+/g, ' ').slice(0, 200));
        }
    });
    log('opping bot...');
    await rcon(`op ${BOT_NAME}`);
    await rcon(`gamemode creative ${BOT_NAME}`);
    await sleep(500);

    const logMarker = getLogTailMarker();
    const results = [];

    const cases = [
        ['NBT chest',          () => testNbtChest(bot)],
        ['Sign',               () => testSign(bot)],
        ['Banner',             () => testBanner(bot)],
        ['Jukebox',            () => testJukebox(bot)],
        ['Decorated pot',      () => testDecoratedPot(bot)],
        ['Bed (multi-block)',  () => testBed(bot)],
        ['Door (multi-block)', () => testDoor(bot)],
        ['Sand column undo',   () => testSandColumnUndo(bot)],
        ['Mixed density',      () => testMixedDensity(bot)],
        ['Item frame entity',  () => testItemFrame(bot)],
        ['Scale 10k',          () => testScale(bot, 10000, 80)],
        ['Scale 100k',         () => testScale(bot, 100000, 130)],
        ['Scale 500k',         () => testScale(bot, 500000, 200)],
    ];

    for (const [label, fn] of cases) {
        log(`\n--- ${label} ---`);
        try {
            const r = await fn();
            r.label = label;
            results.push(r);
            log(`  ${r.ok ? 'PASS' : 'FAIL'}: ${JSON.stringify(r).slice(0, 250)}`);
        } catch (e) {
            log(`  THREW: ${e.message}`);
            results.push({ label, ok: false, threw: e.message });
        }
        await sleep(1500);
    }

    bot.quit();

    const fallbacks = checkFallbackWarnings(logMarker);
    const exceptions = checkExceptions(logMarker);

    console.log('\n\n================ FINDINGS ================');
    console.log('\nPASS:');
    for (const r of results.filter(r => r.ok)) {
        console.log(`  ✓ ${r.label || r.id}`);
    }
    console.log('\nFAIL:');
    for (const r of results.filter(r => !r.ok)) {
        const detail = r.threw ? `threw: ${r.threw}` : (r.reason || JSON.stringify(r).slice(0, 200));
        console.log(`  ✗ ${r.label || r.id} — ${detail}`);
    }
    console.log('\nNMS fallback warnings:', fallbacks.length);
    for (const w of fallbacks.slice(0, 3)) console.log('  ' + w);
    console.log('\nServer-side Spyglass exceptions:', exceptions.length);
    for (const e of exceptions.slice(0, 5)) console.log('  ' + e);
    console.log('\nFull JSON:');
    for (const r of results) console.log('  ' + JSON.stringify(r));

    process.exit(results.some(r => !r.ok) ? 1 : 0);
}

run().catch(e => { console.error(e); process.exit(2); });
