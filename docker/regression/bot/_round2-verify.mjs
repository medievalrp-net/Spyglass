// Live verification of the snapshot round-2 fixes (throwaway 25590/25580).
import mineflayer from 'mineflayer';
import net from 'net';
import fs from 'fs';
import v from 'vec3';

const HOST = '127.0.0.1', PORT = 25590, RCON_PORT = 25580, PASS = 'test123';
// Point SG_SERVER_DIR at the throwaway server root (needs server8.log, a
// console.in FIFO wired to the server's stdin, and plugins/Spyglass/spyglass.db).
const SB = process.env.SG_SERVER_DIR;
if (!SB) { console.error('set SG_SERVER_DIR=<throwaway server root>'); process.exit(2); }
const LOG = SB + '/server8.log';
const BOT = 'r2' + Date.now().toString(36).slice(-4);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);
let pass = 0, fail = 0; const results = [];
const check = (name, ok, detail) => { if (ok) { pass++; log(`  PASS  ${name}`); } else { fail++; log(`  FAIL  ${name} :: ${detail}`); } results.push({ name, ok, detail }); };

function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 }); let st = 0, bs = []; s.on('error', rej); s.on('timeout', () => { s.destroy(); rej('t/o'); }); s.on('connect', () => s.write(pkt(1, 3, PASS))); s.on('data', c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, '')); } }); }); }
function logMark() { try { return fs.statSync(LOG).size; } catch { return 0; } }
function logSince(m) { const fd = fs.openSync(LOG, 'r'); const size = fs.fstatSync(fd).size; if (size <= m) { fs.closeSync(fd); return ''; } const buf = Buffer.alloc(size - m); fs.readSync(fd, buf, 0, size - m, m); fs.closeSync(fd); return buf.toString('utf8'); }
async function consoleQuery(cmd, waitMs = 6000, until = null) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const m = logMark(); fs.appendFileSync(SB + '/console.in', cmd + '\n');
    const dl = Date.now() + waitMs; let out = '';
    while (Date.now() < dl) { await sleep(500); out = logSince(m); if (until && until.test(out)) return out; }
    if (out.trim() !== '') return out;
  }
  return '';
}

let bot = null, chatBuf = [];
const armChat = () => { chatBuf = []; };
const chatHas = re => chatBuf.some(m => re.test(m));
async function waitChatRe(re, t = 6000) { const dl = Date.now() + t; while (Date.now() < dl) { if (chatHas(re)) return true; await sleep(200); } return false; }
function waitWindow(t = 6000) { return new Promise(res => { const h = w => { clearTimeout(timer); res(w); }; const timer = setTimeout(() => { bot.removeListener('windowOpen', h); res(null); }, t); bot.once('windowOpen', h); }); }
async function closeWin(w) { try { bot.closeWindow(w); } catch { } await sleep(400); }

(async () => {
  log(`=== round-2 verification (bot=${BOT}) ===`);
  const BX = 23000, BY = 72, BZ = 23000;
  await rcon(`forceload add ${BX - 16} ${BZ - 16} ${BX + 48} ${BZ + 16}`); await sleep(500);
  await rcon(`fill ${BX - 4} ${BY - 1} ${BZ - 4} ${BX + 40} ${BY - 1} ${BZ + 12} minecraft:stone`); await sleep(500);

  bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.8' });
  bot.on('messagestr', m => chatBuf.push(m));
  await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
  await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
  await rcon(`tp ${BOT} ${BX} ${BY} ${BZ}`); await sleep(2500);
  try { await bot.waitForChunksToLoad(); } catch { }
  await sleep(1000);

  // ---- 1. typo'd trg: on plain stone: chat error, no crash, no window ----
  log('--- typo trg ---');
  armChat();
  const w0 = waitWindow(4000);
  bot.chat(`/sg snapshot trg:${BX - 2},${BY - 1},${BZ} t:2m`);
  const win0 = await w0;
  await waitChatRe(/Not a container/, 3000);
  check('typo trg: gets a chat answer', chatHas(/Not a container, and no container history/), chatBuf.slice(-4).join(' | '));
  check('typo trg: opens no window', win0 == null, 'a window opened');
  const errs0 = await consoleQuery('list', 2000); // flush marker
  check('typo trg: no exception logged', !/IllegalArgumentException|Could not pass/.test(logSince(0).slice(-4000)), 'exception in log');

  // ---- 2. world border + ungenerated chunk guards ----
  log('--- trg guards ---');
  let out = await consoleQuery('spyglass snapshot trg:30000010,64,0 w:world t:1h', 5000, /world border/);
  check('outside-border trg refused', /outside the world border/.test(out), out.slice(-200));
  out = await consoleQuery('spyglass snapshot trg:100000,64,100000 w:world t:1h', 5000, /never been generated|Not a container/);
  check('virgin-chunk trg refused without generating', /never been generated/.test(out), out.slice(-200));
  let regionHits = 0;
  const regionDir = SB + '/world/region';
  if (fs.existsSync(regionDir)) {
    regionHits = fs.readdirSync(regionDir).filter(f => f.startsWith('r.195.')).length;
  }
  check('no region file created for 100000,100000', regionHits === 0, 'region files: ' + regionHits);

  // ---- 3. double chest: GUI opens (54 cells), info lands in chat ----
  log('--- double chest GUI ---');
  const D = { x: BX + 6, z: BZ + 5 };
  await rcon(`setblock ${D.x} ${BY} ${D.z} minecraft:chest[facing=north,type=right]{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:9}]}`); await sleep(200);
  await rcon(`setblock ${D.x + 1} ${BY} ${D.z} minecraft:chest[facing=north,type=left]{Items:[{Slot:3b,id:"minecraft:gold_ingot",count:4}]}`); await sleep(500);
  let w1 = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    armChat();
    const w1p = waitWindow(6000);
    bot.chat(`/sg snapshot trg:${D.x},${BY},${D.z} t:2m`);
    w1 = await w1p;
    if (w1 && w1.slots.length - 36 === 54) break;
    if (w1) await closeWin(w1);
    // The halves did not merge with this left/right pairing - swap them.
    await rcon(`setblock ${D.x} ${BY} ${D.z} minecraft:chest[facing=north,type=left]{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:9}]}`); await sleep(200);
    await rcon(`setblock ${D.x + 1} ${BY} ${D.z} minecraft:chest[facing=north,type=right]{Items:[{Slot:3b,id:"minecraft:gold_ingot",count:4}]}`); await sleep(500);
  }
  check('double-chest GUI opens', w1 != null, 'no window');
  if (w1) {
    const cells = w1.slots.length - 36;
    check('double-chest GUI has exactly 54 content cells', cells === 54, 'cells=' + cells);
    await closeWin(w1);
  }
  check('double-chest info arrived in chat', chatHas(/as of .* ago/), chatBuf.slice(-5).join(' | '));

  // ---- 4. destroyed container with history: GUI opens + history-gap note ----
  log('--- destroyed container ---');
  const C = { x: BX + 12, z: BZ + 5 };
  await rcon(`setblock ${C.x} ${BY - 1} ${C.z} minecraft:hopper`); await sleep(200);
  await rcon(`setblock ${C.x} ${BY} ${C.z} minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:emerald",count:5}]}`); await sleep(4000); // hopper drains -> records
  await rcon(`setblock ${C.x} ${BY} ${C.z} minecraft:air`); await sleep(500);
  out = await consoleQuery(`spyglass snapshot trg:${C.x},${BY},${C.z} w:world t:20s`, 6000, /no longer present/);
  check('destroyed chest still reconstructs (console)', /no longer present/.test(out), out.slice(-300));
  armChat();
  const w2p = waitWindow(6000);
  bot.chat(`/sg snapshot trg:${C.x},${BY},${C.z} t:20s`);
  const w2 = await w2p;
  check('destroyed-container GUI opens (was #351 crash)', w2 != null, 'no window; chat: ' + chatBuf.slice(-4).join(' | '));
  if (w2) await closeWin(w2);
  // history-gap note: ask for a time before the chest's first record
  out = await consoleQuery(`spyglass snapshot trg:${C.x},${BY},${C.z} w:world t:2h`, 6000, /older than/);
  check('pre-history t: carries the inference note', /older than this container's recorded history/.test(out), out.slice(-400));

  // ---- 5. chiseled bookshelf + decorated pot ----
  log('--- bookshelf / pot ---');
  const B = { x: BX + 18, z: BZ + 5 };
  await rcon(`setblock ${B.x} ${BY} ${B.z} minecraft:chiseled_bookshelf[facing=south]`); await sleep(300);
  await rcon(`item replace block ${B.x} ${BY} ${B.z} container.2 with minecraft:book 1`); await sleep(300);
  out = await consoleQuery(`spyglass snapshot trg:${B.x},${BY},${B.z} w:world t:1m`, 6000, /CHISELED|BOOK|Nothing/);
  check('bookshelf recognised via trg:', /CHISELED_BOOKSHELF/.test(out) && !/no longer present|Not a container/.test(out), out.slice(-300));
  check('bookshelf live book listed', /BOOK x1/.test(out), out.slice(-300));
  const P = { x: BX + 22, z: BZ + 5 };
  await rcon(`setblock ${P.x} ${BY} ${P.z} minecraft:decorated_pot`); await sleep(300);
  await rcon(`item replace block ${P.x} ${BY} ${P.z} container.0 with minecraft:diamond 3`); await sleep(300);
  out = await consoleQuery(`spyglass snapshot trg:${P.x},${BY},${P.z} w:world t:1m`, 6000, /DECORATED|DIAMOND|Nothing/);
  check('pot recognised via trg:', /DECORATED_POT/.test(out) && !/no longer present|Not a container/.test(out), out.slice(-300));
  check('pot live diamonds listed', /DIAMOND x3/.test(out), out.slice(-300));
  // look-at path for the bookshelf
  await rcon(`tp ${BOT} ${B.x} ${BY} ${B.z + 2}`); await sleep(1500);
  await bot.lookAt(v(B.x + 0.5, BY + 0.5, B.z + 0.5), true); await sleep(500);
  armChat();
  const w3p = waitWindow(5000);
  bot.chat('/sg snapshot t:1m');
  const w3 = await w3p;
  check('bookshelf look-at opens a GUI (was "Not a container")', w3 != null, 'no window; chat: ' + chatBuf.slice(-3).join(' | '));
  if (w3) await closeWin(w3);

  // ---- 6. player-mode text listing shows capture time ----
  log('--- capture line ---');
  await rcon(`clear ${BOT}`); await rcon(`give ${BOT} minecraft:redstone 7`); await sleep(8000);
  out = await consoleQuery(`spyglass snapshot p:${BOT} t:1s`, 8000, /captured/);
  check('console listing names the capture instant', /captured 20\d\d-/.test(out), out.slice(-400));
  check('console listing names the cause', /\((sweep|join|death|world change|quit)\)/.test(out), out.slice(-400));

  bot.quit();
  await rcon(`forceload remove ${BX - 16} ${BZ - 16} ${BX + 48} ${BZ + 16}`);
  log(`\n=== RESULT: ${pass} passed, ${fail} failed ===`);
  for (const r of results) log(`   ${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : '  :: ' + r.detail}`);
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e?.message || e); process.exit(2); });
