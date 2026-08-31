// FAWE break-vs-place probe — user report: "[FAWE] only ever shows BREAK, never place".
// Runs the #105 op matrix against an isolated throwaway Paper 1.21.8 + FAWE 2.15.0
// server (ports 25590/25580, SQLite backend) and prints exact per-op
// break/place counts straight from the SQLite records table.
// Standalone on purpose: regression/bot/cases/lib.js and scenario-worldedit.js
// belong to the shared harness (RP_Server ports + ClickHouse) — do not edit them.
import mineflayer from 'mineflayer';
import net from 'net';
import { execFileSync } from 'child_process';

const HOST = '127.0.0.1', PORT = 25590, RCON_PORT = 25580, PASS = 'test123';
// Point SG_DB at the throwaway server's SQLite file (see the header notes).
const DB = process.env.SG_DB;
if (!DB) { console.error('set SG_DB=<path to the server\'s plugins/Spyglass/spyglass.db>'); process.exit(2); }
const BOT = 'fw' + Date.now().toString(36).slice(-5);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);

function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 }); let st = 0, bs = []; s.on('error', rej); s.on('timeout', () => { s.destroy(); rej('t/o'); }); s.on('connect', () => s.write(pkt(1, 3, PASS))); s.on('data', c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, '')); } }); }); }
function waitChat(bot, re, t) { return new Promise(r => { const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); r(m); } }; bot.on('messagestr', h); setTimeout(() => { bot.removeListener('messagestr', h); r(null); }, t); }); }

function sql(q) {
  try {
    return execFileSync('sqlite3', ['-readonly', DB, q], { encoding: 'utf8' }).trim();
  } catch (e) { return 'SQLERR:' + (e.stderr || e.message); }
}
const BASE = `FROM records r JOIN dict ed ON r.event=ed.id JOIN dict od ON r.origin_kind=od.id JOIN uuids pu ON r.player=pu.id LEFT JOIN dict td ON r.target=td.id WHERE od.val='fawe' AND pu.name='${BOT}'`;
function cnt(extra = '') { const v = sql(`SELECT COUNT(*) ${BASE}${extra ? ' AND ' + extra : ''};`); const n = parseInt(v); return Number.isFinite(n) ? n : 0; }
function dump(boxSql) { return sql(`SELECT ed.val || '/' || COALESCE(td.val,'?') || '=' || COUNT(*) ${BASE} AND ${boxSql} GROUP BY ed.val, td.val ORDER BY 1;`).replace(/\n/g, ' '); }
async function pollCnt(extra, min, timeoutMs = 20000) { const dl = Date.now() + timeoutMs; let c = 0; while (Date.now() < dl) { c = cnt(extra); if (c >= min) return c; await sleep(1000); } return c; }
async function settle(stableMs = 3000, timeoutMs = 25000) { const dl = Date.now() + timeoutMs; let last = -1, lc = Date.now(); while (Date.now() < dl) { const c = cnt(); if (c !== last) { last = c; lc = Date.now(); } else if (Date.now() - lc >= stableMs) return c; await sleep(1000); } return last; }

let pass = 0, fail = 0; const results = [];
const check = (name, ok, detail) => { if (ok) { pass++; log(`  PASS  ${name}`); } else { fail++; log(`  FAIL  ${name} — ${detail}`); } results.push({ name, ok, detail }); };

const BX = 17000, BY = 72, BZ = 17000, SZ = 5;
const VOLUME = SZ * SZ * SZ;
const WALL_VOLUME = (SZ * SZ - (SZ - 2) * (SZ - 2)) * SZ;
const COLUMN_AREA = SZ * SZ;
let laneN = 0;
function lane() { const x0 = BX + (laneN++) * 12; return { x0, y0: BY, z0: BZ, x1: x0 + SZ - 1, y1: BY + SZ - 1, z1: BZ + SZ - 1 }; }
function box(L) { return `x BETWEEN ${L.x0} AND ${L.x1} AND y BETWEEN ${L.y0} AND ${L.y1} AND z BETWEEN ${L.z0} AND ${L.z1}`; }
function shiftedBox(L, dx, dy = 0, dz = 0) { return box({ x0: L.x0 + dx, y0: L.y0 + dy, z0: L.z0 + dz, x1: L.x1 + dx, y1: L.y1 + dy, z1: L.z1 + dz }); }

let bot = null;
async function select(L) {
  bot.chat('//sel cuboid'); await sleep(200);
  bot.chat(`//pos1 ${L.x0},${L.y0},${L.z0}`); await sleep(200);
  bot.chat(`//pos2 ${L.x1},${L.y1},${L.z1}`); await sleep(200);
}
const DONE_RE = /have been changed|operation completed|blocks? affected|pasted|stacked|moved|Created/i;

(async () => {
  log(`=== FAWE break-vs-place probe (bot=${BOT}) ===`);
  const fx0 = BX - 16, fz0 = BZ - 16, fx1 = BX + 220, fz1 = BZ + 16;
  await rcon(`forceload add ${fx0} ${fz0} ${fx1} ${fz1}`); await sleep(800);

  bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.8' });
  await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
  await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
  await rcon(`tp ${BOT} ${BX} ${BY + 1} ${BZ}`); await sleep(2500);
  try { await bot.waitForChunksToLoad(); } catch { }
  await sleep(1500);
  bot.chat('//limit -1'); await sleep(300);

  async function selOp(label, baseMat, opCmd, verify) {
    const L = lane();
    log(`--- ${label} ---`);
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} ${baseMat || 'air'}`); await sleep(300);
    await select(L);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat(opCmd); await done;
    await settle();
    await verify(L);
    log(`  [rows] ${dump(box(L))}`);
    return L;
  }
  async function expectCount(name, extra, expected, timeoutMs = 20000) {
    const got = await pollCnt(extra, expected, timeoutMs);
    check(`${name} (${got}/${expected})`, got === expected, `expected ${expected}, got ${got}`);
    return got;
  }
  async function expectDelta(name, extra, before, expected, timeoutMs = 20000) {
    const got = await pollCnt(extra, before + expected, timeoutMs);
    const delta = got - before;
    check(`${name} (D=${delta}/${expected})`, delta === expected, `before=${before} after=${got} expected D=${expected}`);
    return got;
  }

  // 1. //set
  await selOp('set', 'stone', '//set glass', async (L) => {
    await expectCount('//set place GLASS', `ed.val='place' AND td.val='GLASS' AND ${box(L)}`, VOLUME);
    await expectCount('//set break STONE', `ed.val='break' AND td.val='STONE' AND ${box(L)}`, VOLUME);
  });

  // 2. //replace
  await selOp('replace', 'cobblestone', '//replace cobblestone sandstone', async (L) => {
    await expectCount('//replace place SANDSTONE', `ed.val='place' AND td.val='SANDSTONE' AND ${box(L)}`, VOLUME);
    await expectCount('//replace break COBBLESTONE', `ed.val='break' AND td.val='COBBLESTONE' AND ${box(L)}`, VOLUME);
  });

  // 3. //walls
  await selOp('walls', null, '//walls bricks', async (L) => {
    await expectCount('//walls place BRICKS', `ed.val='place' AND td.val='BRICKS' AND ${box(L)}`, WALL_VOLUME);
    await expectCount('//walls NO spurious break BRICKS', `ed.val='break' AND td.val='BRICKS' AND ${box(L)}`, 0, 3000);
  });

  // 4. //stack
  {
    const L = lane(); log('--- stack ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} emerald_block`); await sleep(250);
    await rcon(`fill ${L.x1 + 1} ${L.y0} ${L.z0} ${L.x1 + SZ} ${L.y1} ${L.z1} air`); await sleep(250);
    await select(L);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//stack 1 east'); await done; await settle();
    await expectCount('//stack place EMERALD_BLOCK', `ed.val='place' AND td.val='EMERALD_BLOCK' AND ${shiftedBox(L, SZ)}`, VOLUME);
    await expectCount('//stack NO spurious break@dst', `ed.val='break' AND td.val='EMERALD_BLOCK' AND ${shiftedBox(L, SZ)}`, 0, 3000);
    await expectCount('//stack NO records@src', box(L), 0, 3000);
    log(`  [rows src] ${dump(box(L))}`); log(`  [rows dst] ${dump(shiftedBox(L, SZ))}`);
  }

  // 5. //move
  {
    const L = lane(); log('--- move ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} lapis_block`); await sleep(250);
    await rcon(`fill ${L.x0 + 6} ${L.y0} ${L.z0} ${L.x0 + 6 + SZ - 1} ${L.y1} ${L.z1} air`); await sleep(250);
    await select(L);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//move 6 east'); await done; await settle();
    await expectCount('//move break LAPIS_BLOCK@src', `ed.val='break' AND td.val='LAPIS_BLOCK' AND ${box(L)}`, VOLUME);
    await expectCount('//move place LAPIS_BLOCK@dst', `ed.val='place' AND td.val='LAPIS_BLOCK' AND ${shiftedBox(L, 6)}`, VOLUME);
    await expectCount('//move NO spurious break@dst', `ed.val='break' AND td.val='LAPIS_BLOCK' AND ${shiftedBox(L, 6)}`, 0, 3000);
    log(`  [rows src] ${dump(box(L))}`); log(`  [rows dst] ${dump(shiftedBox(L, 6))}`);
  }

  // 6. //copy + //paste -o
  {
    const L = lane(); log('--- copy/paste ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} gold_block`); await sleep(300);
    await select(L);
    bot.chat('//copy'); await sleep(900);
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} air`); await sleep(400);
    const before = cnt(`ed.val='place' AND td.val='GOLD_BLOCK' AND ${box(L)}`);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//paste -o'); await done; await settle();
    await expectDelta('//paste place GOLD_BLOCK', `ed.val='place' AND td.val='GOLD_BLOCK' AND ${box(L)}`, before, VOLUME);
    await expectCount('//paste NO spurious break GOLD', `ed.val='break' AND td.val='GOLD_BLOCK' AND ${box(L)}`, 0, 3000);
    log(`  [rows] ${dump(box(L))}`);
  }

  // 7. //schem save/load/paste
  {
    const L = lane(); log('--- schematic paste ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} diamond_block`); await sleep(300);
    await select(L);
    bot.chat('//copy'); await sleep(900);
    bot.chat('//schem save sgprobe1'); await sleep(1200);
    bot.chat('//schem load sgprobe1'); await sleep(1200);
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} air`); await sleep(400);
    const before = cnt(`ed.val='place' AND td.val='DIAMOND_BLOCK' AND ${box(L)}`);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//paste -o'); await done; await settle();
    await expectDelta('schem //paste place DIAMOND_BLOCK', `ed.val='place' AND td.val='DIAMOND_BLOCK' AND ${box(L)}`, before, VOLUME);
    bot.chat('//schem delete sgprobe1'); await sleep(400);
    log(`  [rows] ${dump(box(L))}`);
  }

  // 8. //undo (+ the //set obsidian before it)
  const undoL = lane();
  {
    const L = undoL; log('--- undo ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} stone`); await sleep(300);
    await select(L);
    const pBefore = cnt(`ed.val='place' AND td.val='OBSIDIAN' AND ${box(L)}`);
    let done = waitChat(bot, DONE_RE, 30000); bot.chat('//set obsidian'); await done; await settle();
    await expectDelta('//set place OBSIDIAN (pre-undo)', `ed.val='place' AND td.val='OBSIDIAN' AND ${box(L)}`, pBefore, VOLUME);
    const before = cnt(box(undoL));
    const bBefore = cnt(`ed.val='break' AND td.val='OBSIDIAN' AND ${box(L)}`);
    done = waitChat(bot, /Undid|undone/i, 30000); bot.chat('//undo'); await done; await settle();
    const total = cnt(box(L));
    check(`//undo logged exactly (D=${total - before}/${VOLUME * 2})`, total - before === VOLUME * 2, `before=${before} total=${total}`);
    await expectDelta('//undo break OBSIDIAN', `ed.val='break' AND td.val='OBSIDIAN' AND ${box(L)}`, bBefore, VOLUME, 8000);
    log(`  [rows] ${dump(box(L))}`);
  }

  // 9. //redo
  {
    log('--- redo ---');
    const before = cnt(box(undoL));
    const done = waitChat(bot, /Redid|redone/i, 30000); bot.chat('//redo'); await done; await settle();
    const total = cnt(box(undoL));
    check(`//redo logged exactly (D=${total - before}/${VOLUME * 2})`, total - before === VOLUME * 2, `before=${before} total=${total}`);
    log(`  [rows] ${dump(box(undoL))}`);
  }

  // 10. generation
  let genIdx = 0;
  async function genOp(label, cmd, target) {
    log(`--- ${label} (generation) ---`);
    const cx = BX + 120 + (genIdx++) * 14, cz = BZ;
    await rcon(`fill ${cx - 4} ${BY - 1} ${cz - 4} ${cx + 4} ${BY - 1} ${cz + 4} stone`); await sleep(300);
    await rcon(`tp ${BOT} ${cx} ${BY} ${cz}`); await sleep(1500);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat(cmd); await done; await sleep(500);
    const places = await pollCnt(`ed.val='place' AND td.val='${target}'`, 1);
    check(`${label} place ${target} (${places})`, places >= 1, `got ${places}`);
    const selfBreaks = cnt(`ed.val='break' AND td.val='${target}'`);
    check(`${label} NO spurious break ${target} (${selfBreaks})`, selfBreaks === 0, `got ${selfBreaks}`);
  }
  await genOp('//sphere', '//sphere mossy_cobblestone 3', 'MOSSY_COBBLESTONE');
  await genOp('//cyl', '//cyl nether_bricks 3 2', 'NETHER_BRICKS');
  await genOp('//pyramid', '//pyramid quartz_block 3', 'QUARTZ_BLOCK');

  // 11. brush (best-effort)
  try {
    log('--- brush (sphere) ---');
    const cx = BX + 200, cz = BZ;
    await rcon(`fill ${cx - 5} ${BY - 1} ${cz - 5} ${cx + 5} ${BY + 8} ${cz + 5} air`); await sleep(200);
    await rcon(`tp ${BOT} ${cx} ${BY + 6} ${cz}`); await sleep(1500);
    await rcon(`give ${BOT} minecraft:stick`); await sleep(600);
    bot.setQuickBarSlot(0); await sleep(300);
    bot.chat('//brush sphere prismarine 2'); await sleep(600);
    await bot.look(0, -Math.PI / 2, true); await sleep(600);
    for (let i = 0; i < 3; i++) { bot.activateItem(); await sleep(700); if (bot.deactivateItem) bot.deactivateItem(); await sleep(300); }
    const probe = await rcon(`execute if block ${cx} ${BY} ${cz} prismarine run say BRUSH_HIT_${BOT}`);
    const fired = /BRUSH_HIT/.test(probe);
    const places = await pollCnt(`ed.val='place' AND td.val='PRISMARINE'`, 1, 12000);
    if (!fired && places === 0) {
      log('  SKIP  brush — inconclusive (bot aim/range)');
      results.push({ name: 'brush', ok: true, detail: 'skipped', skipped: true });
    } else {
      check(`brush place PRISMARINE (fired=${fired}, places=${places})`, places >= 1, `fired=${fired} places=${places}`);
      const brushBreaks = cnt(`ed.val='break' AND td.val='PRISMARINE'`);
      check(`brush NO spurious break PRISMARINE (${brushBreaks})`, brushBreaks === 0, `got ${brushBreaks}`);
    }
  } catch (e) { log('  SKIP  brush — ' + (e?.message || e)); results.push({ name: 'brush', ok: true, detail: 'skipped/error', skipped: true }); }

  log('overall event totals: ' + sql(`SELECT ed.val || '=' || COUNT(*) ${BASE} GROUP BY 1;`));
  bot.quit();
  await rcon(`forceload remove ${fx0} ${fz0} ${fx1} ${fz1}`);
  log(`\n=== RESULT: ${pass} passed, ${fail} failed ===`);
  for (const r of results) log(`   ${r.skipped ? 'SKIP' : r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : '  — ' + r.detail}`);
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e?.message || e); process.exit(2); });
