// /sg snapshot (#341) user-story battery against the throwaway server.
// Phase order matters: every real-container interaction (openContainer) runs
// BEFORE any plugin-GUI interaction - after the bot touches an InvUI window,
// mineflayer's openContainer wedges (windowOpen never fires again for real
// blocks), while server-pushed GUI windows keep working.
// Console commands go through the server-console FIFO; ground truth via SQLite.
import mineflayer from 'mineflayer';
import net from 'net';
import fs from 'fs';
import { execFileSync } from 'child_process';
import v from 'vec3';

const HOST = '127.0.0.1', PORT = 25590, RCON_PORT = 25580, PASS = 'test123';
// Point SG_SERVER_DIR at the throwaway server root (needs server8.log,
// console.in FIFO wired to the server's stdin, plugins/Spyglass/spyglass.db).
const SB = process.env.SG_SERVER_DIR;
if (!SB) { console.error('set SG_SERVER_DIR=<throwaway server root>'); process.exit(2); }
const LOG = SB + '/server8.log';
const FIFO = SB + '/console.in';
const DB = SB + '/plugins/Spyglass/spyglass.db';
const BOT = 'sn' + Date.now().toString(36).slice(-4);
const BOT2 = 'sm' + Date.now().toString(36).slice(-4);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);
let pass = 0, fail = 0; const results = [];
const check = (name, ok, detail) => { if (ok) { pass++; log(`  PASS  ${name}`); } else { fail++; log(`  FAIL  ${name} :: ${detail}`); } results.push({ name, ok, detail }); };

function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 }); let st = 0, bs = []; s.on('error', rej); s.on('timeout', () => { s.destroy(); rej('t/o'); }); s.on('connect', () => s.write(pkt(1, 3, PASS))); s.on('data', c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, '')); } }); }); }

function logMark() { try { return fs.statSync(LOG).size; } catch { return 0; } }
function logSince(mark) { const fd = fs.openSync(LOG, 'r'); const size = fs.fstatSync(fd).size; const len = size - mark; if (len <= 0) { fs.closeSync(fd); return ''; } const buf = Buffer.alloc(len); fs.readSync(fd, buf, 0, len, mark); fs.closeSync(fd); return buf.toString('utf8'); }
function consoleCmd(cmd) { fs.appendFileSync(FIFO, cmd + '\n'); }
async function consoleQuery(cmd, waitMs = 5000, until = null) {
  // The FIFO occasionally drops a line before the console reads it (about
  // one command in fifteen); a silent window gets one resend.
  for (let attempt = 0; attempt < 2; attempt++) {
    const m = logMark(); consoleCmd(cmd);
    const dl = Date.now() + waitMs;
    let out = '';
    while (Date.now() < dl) {
      await sleep(500);
      out = logSince(m);
      if (until && until.test(out)) return out;
    }
    if (out.trim() !== '') return out;
  }
  return '';
}

function sql(q) { try { return execFileSync('sqlite3', ['-cmd', '.timeout 4000', '-readonly', DB, q], { encoding: 'utf8' }).trim(); } catch (e) { return 'SQLERR:' + (e.stderr || e.message); } }

let bot = null;
let chatBuf = [];
function armChat() { chatBuf = []; }
function chatHas(re) { return chatBuf.some(m => re.test(m)); }
async function waitChatRe(re, t = 6000) { const dl = Date.now() + t; while (Date.now() < dl) { if (chatHas(re)) return true; await sleep(200); } return false; }
function waitWindow(t = 6000) {
  return new Promise(res => {
    const h = w => { clearTimeout(timer); res(w); };
    const timer = setTimeout(() => { bot.removeListener('windowOpen', h); res(null); }, t);
    bot.once('windowOpen', h);
  });
}
function windowItems(w) { const n = w.slots.length - 36; return w.slots.slice(0, n).map((it, i) => it ? { i, name: it.name, count: it.count } : null).filter(Boolean); }
async function closeWin(w) { try { bot.closeWindow(w); } catch { } await sleep(400); }
function invCount(name) { return bot.inventory.items().filter(it => it.name === name).reduce((a, b) => a + b.count, 0); }
async function openWithRetry(block, tries = 3) {
  for (let i = 0; i < tries; i++) {
    try {
      await bot.lookAt(block.position.offset(0.5, 0.5, 0.5), true); await sleep(400);
      return await bot.openContainer(block);
    } catch (e) {
      log(`  (openContainer retry ${i + 1}: ${e.message})`);
      bot.setControlState('forward', true); await sleep(250); bot.setControlState('forward', false);
      await sleep(800);
    }
  }
  throw new Error('openContainer failed after retries');
}

(async () => {
  log(`=== snapshot battery (bot=${BOT}) ===`);
  const BX = 20000, BY = 72, BZ = 20000;
  await rcon(`forceload add ${BX - 16} ${BZ - 16} ${BX + 48} ${BZ + 16}`); await sleep(500);
  // Solid ground: the flat world's surface is y=-60, so an un-platformed bot
  // free-falls after teleport and every block interact misses on reach.
  await rcon(`fill ${BX - 4} ${BY - 1} ${BZ - 4} ${BX + 40} ${BY - 1} ${BZ + 12} minecraft:stone`); await sleep(500);

  bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.8' });
  bot.on('messagestr', m => { chatBuf.push(m); });
  await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
  await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
  await rcon(`tp ${BOT} ${BX} ${BY} ${BZ}`); await sleep(2500);
  try { await bot.waitForChunksToLoad(); } catch { }
  await sleep(1000);

  // ================= PHASE 1: real-container interactions =================
  log('--- phase 1: chest deposits/withdrawals (before any GUI) ---');
  const C1 = { x: BX + 10, y: BY, z: BZ + 5 };
  await rcon(`setblock ${C1.x} ${C1.y} ${C1.z} minecraft:chest[facing=north]`); await sleep(400);
  await rcon(`clear ${BOT}`); await rcon(`give ${BOT} minecraft:iron_ingot 7`); await sleep(600);
  await rcon(`tp ${BOT} ${C1.x} ${C1.y} ${C1.z + 2}`); await sleep(2000);
  const chestBlock = bot.blockAt(v(C1.x, C1.y, C1.z));
  let cont = await openWithRetry(chestBlock);
  await cont.deposit(bot.registry.itemsByName.iron_ingot.id, null, 7);
  await sleep(600); await closeWin(cont);
  await sleep(1500);
  const markC = Date.now();
  cont = await openWithRetry(chestBlock);
  await cont.withdraw(bot.registry.itemsByName.iron_ingot.id, null, 7);
  await sleep(600); await closeWin(cont);
  await sleep(1500);
  const backC = () => Math.ceil((Date.now() - markC) / 1000);

  // hopper chest: REDSTONE-LOCKED hopper below gives a controllable window
  // (deposit while locked, then unlock and let it drain). Slot-accurate
  // transfer records must reverse-apply the drain so the locked instant
  // reads back exactly.
  const C2 = { x: BX + 14, y: BY, z: BZ + 5 };
  await rcon(`setblock ${C2.x} ${C2.y - 1} ${C2.z} minecraft:hopper`); await sleep(200);
  await rcon(`setblock ${C2.x + 1} ${C2.y - 1} ${C2.z} minecraft:redstone_block`); await sleep(200);
  await rcon(`setblock ${C2.x} ${C2.y} ${C2.z} minecraft:chest[facing=north]`); await sleep(400);
  await rcon(`give ${BOT} minecraft:gold_ingot 5`); await sleep(400);
  await rcon(`tp ${BOT} ${C2.x} ${C2.y} ${C2.z + 2}`); await sleep(1500);
  cont = await openWithRetry(bot.blockAt(v(C2.x, C2.y, C2.z)));
  await cont.deposit(bot.registry.itemsByName.gold_ingot.id, null, 5);
  await sleep(400); await closeWin(cont);
  await sleep(1500);
  const markH = Date.now(); // locked instant: the chest holds exactly 5 gold
  await sleep(2000);
  await rcon(`setblock ${C2.x + 1} ${C2.y - 1} ${C2.z} minecraft:air`); await sleep(200); // unlock
  await sleep(4000); // hopper drains all 5
  const backH = () => Math.ceil((Date.now() - markH) / 1000);

  // double chest
  const D = { x: BX + 22, y: BY, z: BZ + 5 };
  await rcon(`setblock ${D.x} ${D.y} ${D.z} minecraft:chest[facing=north,type=right]`); await sleep(200);
  await rcon(`setblock ${D.x + 1} ${D.y} ${D.z} minecraft:chest[facing=north,type=left]`); await sleep(400);
  await rcon(`tp ${BOT} ${D.x} ${D.y} ${D.z + 2}`); await sleep(1800);
  let dchest = bot.blockAt(v(D.x, D.y, D.z));
  cont = await openWithRetry(dchest);
  let dsize = cont.slots.length - 36;
  if (dsize !== 54) {
    await closeWin(cont);
    await rcon(`setblock ${D.x} ${D.y} ${D.z} minecraft:chest[facing=north,type=left]`); await sleep(200);
    await rcon(`setblock ${D.x + 1} ${D.y} ${D.z} minecraft:chest[facing=north,type=right]`); await sleep(400);
    cont = await openWithRetry(dchest);
    dsize = cont.slots.length - 36;
  }
  check('D1 double chest merged (54 slots)', dsize === 54, 'size=' + dsize);
  let markD = Date.now();
  if (dsize === 54) {
    await rcon(`give ${BOT} minecraft:emerald 4`); await sleep(600);
    const emeraldSlot = cont.slots.findIndex((it, i) => i >= 54 && it && it.name === 'emerald');
    if (emeraldSlot >= 0) {
      await bot.clickWindow(emeraldSlot, 0, 0); await sleep(400);
      await bot.clickWindow(30, 0, 0); await sleep(600); // right-half local slot 3
    }
    await closeWin(cont);
    await sleep(1200);
    markD = Date.now();
    cont = await openWithRetry(dchest);
    await bot.clickWindow(30, 0, 0); await sleep(400);
    const empt = cont.slots.findIndex((it, i) => i >= 54 && it == null);
    if (empt >= 0) { await bot.clickWindow(empt, 0, 0); await sleep(400); }
    await closeWin(cont);
    await sleep(1500);
  }
  const backD = () => Math.ceil((Date.now() - markD) / 1000);
  await rcon(`clear ${BOT}`); await sleep(300);

  // ================= PHASE 2: player-mode captures + console =================
  log('--- phase 2: player mode capture + listing ---');
  await rcon(`give ${BOT} minecraft:cobblestone 32`); await sleep(300);
  await rcon(`give ${BOT} minecraft:diamond_sword 1`); await sleep(8000); // sweep @5s

  let out = await consoleQuery(`spyglass snapshot p:${BOT} t:1s`, 8000);
  check('P1 console listing shows COBBLESTONE x32', /COBBLESTONE x32/.test(out), out.slice(-400));
  check('P1 console listing shows DIAMOND_SWORD', /DIAMOND_SWORD x1/.test(out), out.slice(-400));
  check('P1 header names the subject', new RegExp(BOT + ' as of').test(out), out.slice(-300));

  log('--- phase 2: parse errors ---');
  out = await consoleQuery(`spyglass snapshot p:${BOT}`, 6000, /t: is required/);
  check('P2 missing t: rejected', /t: is required/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot p:${BOT} t:banana`, 6000, /Invalid duration/);
  check('P2 bad duration rejected', /Invalid duration/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot p:${BOT} trg:1,2,3 t:1h`, 6000, /cannot be combined/);
  check('P2 p:+trg: rejected', /cannot be combined/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot p:ZzNoSuch99 t:1h`, 6000, /Unknown player/);
  check('P2 unknown player rejected', /Unknown player/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot t:1h`, 6000, /needs a player|trg:x,y,z/);
  check('P2 console container mode needs trg:', /needs a player|trg:x,y,z/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot trg:1,2,3 t:1h`, 6000, /needs w:/);
  check('P2 console trg: needs w:', /needs w:/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot trg:1,2,3 w:nope t:1h`, 6000, /Unknown world/);
  check('P2 unknown world rejected', /Unknown world/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot p:${BOT} t:30d`, 6000, /No snapshot for/);
  check('P2 no snapshot before T', /No snapshot for/.test(out), out.slice(-300));
  out = await consoleQuery(`spyglass snapshot take 00000000-0000-0000-0000-000000000000 0`, 6000, /as a player/);
  check('P3 console take refused', /as a player/.test(out), out.slice(-300));

  log('--- phase 2: container listings via console ---');
  out = await consoleQuery(`spyglass snapshot trg:${C1.x},${C1.y},${C1.z} w:world t:${backC()}s`);
  check('C1 chest at T shows IRON_INGOT x7', /IRON_INGOT x7/.test(out), out.slice(-400));
  check('C1 reconstruction is certain', !/uncertain/i.test(out), out.slice(-400));

  out = await consoleQuery(`spyglass snapshot trg:${C2.x},${C2.y},${C2.z} w:world t:${backH()}s`);
  check('C4 drained chest reconstructs exactly (GOLD_INGOT x5)', /GOLD_INGOT x5/.test(out), out.slice(-500));
  check('C4 hopper replay is certain (no uncertainty flag)', !/uncertain/i.test(out), out.slice(-500));

  const F = { x: BX + 18, y: BY, z: BZ + 5 };
  await rcon(`setblock ${F.x} ${F.y} ${F.z} minecraft:furnace[facing=north]`); await sleep(400);
  out = await consoleQuery(`spyglass snapshot trg:${F.x},${F.y},${F.z} w:world t:10s`);
  check('C5 furnace flags uncertain (self-mutating)', /self-mutating|uncertain/i.test(out), out.slice(-400));

  if (dsize === 54) {
    out = await consoleQuery(`spyglass snapshot trg:${D.x},${D.y},${D.z} w:world t:${backD()}s`);
    check('D2 double-chest listing shows EMERALD at offset slot', /\[30\] EMERALD x4|\[3\] EMERALD x4/.test(out) && /double/.test(out), out.slice(-500));
  }

  // ================= PHASE 3: GUI flows =================
  log('--- phase 3: player snapshot GUI + takes ---');
  armChat();
  let winP = waitWindow(); bot.chat(`/sg snapshot p:${BOT} t:1s`);
  let w = await winP;
  check('P4 player snapshot GUI opens', w != null, 'no windowOpen');
  let cobbleTaken = false, ironTaken = false;
  if (w) {
    const items = windowItems(w);
    const cobble = items.find(it => it.name === 'cobblestone' && it.count === 32);
    check('P4 GUI shows cobblestone x32', !!cobble, JSON.stringify(items).slice(0, 300));
    const before = invCount('cobblestone');
    if (cobble) {
      await bot.clickWindow(cobble.i, 0, 0); await sleep(1500);
      const after = invCount('cobblestone');
      cobbleTaken = after - before === 32;
      check('P5 GUI take copies exactly 32 cobblestone', cobbleTaken, `before=${before} after=${after}`);
    }
    await rcon(`give ${BOT} minecraft:dirt 2304`); await sleep(800);
    const sword = windowItems(w).find(it => it.name === 'diamond_sword');
    if (sword) {
      armChat();
      await bot.clickWindow(sword.i, 0, 0); await sleep(1200);
      check('P6 full inventory refuses whole-stack take', chatHas(/won't fit/), chatBuf.slice(-4).join(' | '));
    } else {
      check('P6 full inventory refuses whole-stack take', false, 'sword cell not found in GUI');
    }
    await closeWin(w);
  }
  await rcon(`clear ${BOT}`); await sleep(400);

  log('--- phase 3: container GUI via look-at + take ---');
  await rcon(`tp ${BOT} ${C1.x} ${C1.y} ${C1.z + 2}`); await sleep(1500);
  await bot.lookAt(v(C1.x + 0.5, C1.y + 0.5, C1.z + 0.5), true); await sleep(600);
  winP = waitWindow(); bot.chat(`/sg snapshot t:${backC()}s`);
  w = await winP;
  check('C2 look-at chest GUI opens', w != null, 'no windowOpen');
  if (w) {
    const items = windowItems(w);
    const iron = items.find(it => it.name === 'iron_ingot');
    check('C2 GUI shows iron_ingot x7', !!iron && iron.count === 7, JSON.stringify(items).slice(0, 300));
    if (iron) {
      const before = invCount('iron_ingot');
      await bot.clickWindow(iron.i, 0, 0); await sleep(1500);
      const after = invCount('iron_ingot');
      ironTaken = after - before === 7;
      check('C3 container take gives exactly 7 (count-in-blob)', ironTaken, `before=${before} after=${after}`);
    }
    await closeWin(w);
  }

  log('--- phase 3: not a container ---');
  await rcon(`setblock ${BX + 2} ${BY} ${BZ + 5} minecraft:stone`); await sleep(300);
  await rcon(`tp ${BOT} ${BX + 2} ${BY} ${BZ + 7}`); await sleep(1200);
  await bot.lookAt(v(BX + 2.5, BY + 0.5, BZ + 5.5), true); await sleep(600);
  armChat();
  bot.chat('/sg snapshot t:10s'); await waitChatRe(/Not a container/, 4000);
  check('C6 look-at stone rejected', chatHas(/Not a container: STONE/), chatBuf.slice(-4).join(' | '));

  log('--- phase 3: container gone ---');
  await rcon(`setblock ${C1.x} ${C1.y} ${C1.z} minecraft:air`); await sleep(400);
  out = await consoleQuery(`spyglass snapshot trg:${C1.x},${C1.y},${C1.z} w:world t:${backC()}s`);
  check('C7 missing container labeled + uncertain', /no longer present/.test(out), out.slice(-500));
  check('C7 missing container still reconstructs iron', /IRON_INGOT x7/.test(out), out.slice(-500));

  log('--- phase 3: 54-slot GUI sessions (7-row window risk) ---');
  let errMark = logMark();
  winP = waitWindow(5000); bot.chat(`/sg snapshot trg:${C1.x},${C1.y},${C1.z} t:${backC()}s`);
  w = await winP;
  let errOut = logSince(errMark);
  let guiError = /(Exception|ERROR|Could not pass)/.test(errOut);
  check('C8 missing-container GUI opens for a player', w != null && !guiError,
    w == null ? ('no window; log: ' + errOut.split('\n').filter(l => /ERROR|Exception|Caused|at /.test(l)).slice(0, 8).join(' // ')) : ('window ok but log error: ' + errOut.slice(0, 400)));
  if (w) await closeWin(w);

  if (dsize === 54) {
    errMark = logMark();
    winP = waitWindow(5000); bot.chat(`/sg snapshot trg:${D.x},${D.y},${D.z} t:${backD()}s`);
    w = await winP;
    errOut = logSince(errMark);
    guiError = /(Exception|ERROR|Could not pass)/.test(errOut);
    check('D3 double-chest GUI opens for a player', w != null && !guiError,
      w == null ? ('no window; log: ' + errOut.split('\n').filter(l => /ERROR|Exception|Caused|at /.test(l)).slice(0, 8).join(' // ')) : ('window ok but log error: ' + errOut.slice(0, 400)));
    if (w) await closeWin(w);
  }

  log('--- phase 3: token staleness ---');
  armChat();
  bot.chat('/spyglass snapshot take 00000000-0000-0000-0000-000000000000 0');
  await waitChatRe(/expired/, 4000);
  check('T1 wrong token treated as expired', chatHas(/expired/), chatBuf.slice(-4).join(' | '));
  armChat();
  bot.chat('/spyglass snapshot take not-a-uuid 0');
  await waitChatRe(/expired/, 4000);
  check('T2 malformed token treated as expired', chatHas(/expired/), chatBuf.slice(-4).join(' | '));

  // ================= PHASE 4: temporal + offline + death + audit =================
  log('--- phase 4: point-in-time ---');
  await rcon(`clear ${BOT}`); await sleep(300);
  await rcon(`give ${BOT} minecraft:diamond 5`); await sleep(8000);
  const markT = Date.now();
  await rcon(`clear ${BOT}`); await rcon(`give ${BOT} minecraft:iron_ingot 3`); await sleep(8000);
  const backS = Math.ceil((Date.now() - markT) / 1000);
  out = await consoleQuery(`spyglass snapshot p:${BOT} t:${backS}s`, 8000, /x5|x3|Nothing in it/);
  check('P7 past instant shows DIAMOND x5', /DIAMOND x5/.test(out), out.slice(-400));
  check('P7 past instant hides IRON_INGOT', !/IRON_INGOT x3/.test(out), out.slice(-400));

  log('--- phase 4: quit capture + offline lookup ---');
  const bot2 = mineflayer.createBot({ host: HOST, port: PORT, username: BOT2, version: '1.21.8' });
  await new Promise((r, j) => { bot2.once('spawn', r); bot2.once('error', j); });
  await sleep(1500);
  await rcon(`give ${BOT2} minecraft:gold_block 9`); await sleep(1500);
  bot2.quit(); await sleep(2500);
  out = await consoleQuery(`spyglass snapshot p:${BOT2} t:1s`, 8000, /GOLD_BLOCK|Nothing in it|No snapshot/);
  check('P9 offline player lookup shows GOLD_BLOCK x9', /GOLD_BLOCK x9/.test(out), out.slice(-400));

  log('--- phase 4: inventory at death ---');
  await rcon(`clear ${BOT}`); await rcon(`give ${BOT} minecraft:netherite_ingot 2`); await sleep(1200);
  const deathT = Date.now();
  await rcon(`kill ${BOT}`); await sleep(2000);
  try { bot.respawn(); } catch { }
  await sleep(3000);
  const backDth = Math.max(1, Math.floor((Date.now() - deathT) / 1000) - 1);
  out = await consoleQuery(`spyglass snapshot p:${BOT} t:${backDth}s`, 8000, /NETHERITE_INGOT|Nothing in it|No snapshot/);
  check('P8 inventory at death readable (NETHERITE_INGOT x2)', /NETHERITE_INGOT x2/.test(out), out.slice(-400));
  const causes = sql(`SELECT GROUP_CONCAT(DISTINCT cause) FROM player_snapshots;`);
  check('P8 join+sweep capture causes recorded', /join/.test(causes) && /sweep/.test(causes), 'causes=' + causes);

  const cobbleBlobRows = sql(`SELECT COUNT(*) FROM snapshot_items WHERE material='COBBLESTONE';`);
  check('P10 cobblestone payload interned once', cobbleBlobRows === '1', 'rows=' + cobbleBlobRows);

  log('--- phase 4: audit trail ---');
  const takes = sql(`SELECT COUNT(*) FROM records r JOIN dict d ON r.event=d.id WHERE d.val='snapshot-take';`);
  const expectedTakes = (cobbleTaken ? 1 : 0) + (ironTaken ? 1 : 0);
  check('A1 snapshot-take audit rows match successful takes', parseInt(takes) === expectedTakes, `takes=${takes} expected=${expectedTakes}`);

  bot.quit();
  await rcon(`forceload remove ${BX - 16} ${BZ - 16} ${BX + 48} ${BZ + 16}`);
  log(`\n=== RESULT: ${pass} passed, ${fail} failed ===`);
  for (const r of results) log(`   ${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : '  :: ' + r.detail}`);
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e?.message || e); process.exit(2); });
