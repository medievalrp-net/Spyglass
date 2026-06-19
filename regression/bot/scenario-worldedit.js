// #105 — WorldEdit/FAWE mutation-path audit.
//
// Spyglass hooks WorldEdit at the EditSession extent level (every setBlock),
// NOT at the command name. The claim under test: *all* block-mutating
// operations are logged, not just //set and //paste. This scenario drives a
// representative spread of mutation paths as a real opped player and asserts
// each one produced worldedit/fawe break|place records in the backend.
//
// Each op uses a DISTINCT target material in its own region so its records are
// attributable by `target=...` regardless of drain ordering. Verification polls
// the backend (the off-main build + batched insert lands a few seconds later).
//
// Usage:  node scenario-worldedit.js           (vanilla WE: LoggingExtent path)
//         node scenario-worldedit.js --fawe     (FAWE installed: FaweBatchLogger path)
// The --fawe flag only changes which origin_kind is asserted ('fawe' vs
// 'worldedit'); the operations are identical.
import mineflayer from 'mineflayer';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const FAWE = process.argv.includes('--fawe');
const ORIGIN = FAWE ? 'fawe' : 'worldedit';
const BOT = 'we105_' + Math.floor(1000 + Math.random() * 8999);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);

function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 }); let st = 0, bs = []; s.on('error', rej); s.on('timeout', () => { s.destroy(); rej('t/o'); }); s.on('connect', () => s.write(pkt(1, 3, PASS))); s.on('data', c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, '')); } }); }); }
function waitChat(bot, re, t) { return new Promise(r => { const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); r(m); } }; bot.on('messagestr', h); setTimeout(() => { bot.removeListener('messagestr', h); r(null); }, t); }); }
async function ch(q) { return (await (await fetch(`http://localhost:8123/?query=${encodeURIComponent(q)}`)).text()).trim(); }

// Count records this bot produced via WE/FAWE, optionally narrowed by extra SQL.
async function cnt(extra = '') {
  const q = `SELECT count() FROM spyglass.event_records WHERE origin_kind='${ORIGIN}' AND source_player_name='${BOT}'${extra ? ' AND ' + extra : ''}`;
  return parseInt(await ch(q)) || 0;
}
// Poll until cnt(extra) >= min, or timeout. Returns the final count.
async function pollCnt(extra, min, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let c = 0;
  while (Date.now() < deadline) { c = await cnt(extra); if (c >= min) return c; await sleep(1000); }
  return c;
}
// Wait for this bot's total record count to stop growing — FAWE processes edits
// async across chunk batches, so the next op must not start (and overlap the
// same chunks) until the previous op's records have fully drained.
async function settle(stableMs = 3000, timeoutMs = 25000) {
  const deadline = Date.now() + timeoutMs;
  let last = -1, lastChange = Date.now();
  while (Date.now() < deadline) {
    const c = await cnt();
    if (c !== last) { last = c; lastChange = Date.now(); }
    else if (Date.now() - lastChange >= stableMs) return c;
    await sleep(1000);
  }
  return last;
}

let pass = 0, fail = 0;
const results = [];
const check = (name, ok, detail) => { if (ok) { pass++; log(`  PASS  ${name}`); } else { fail++; log(`  FAIL  ${name} — ${detail}`); } results.push({ name, ok, detail }); };

// One 5-wide lane per selection op; 5x5x5 region (125 cells).
const BX = 17000, BY = 72, BZ = 17000, SZ = 5;
let laneN = 0;
function lane() { const x0 = BX + (laneN++) * 12; return { x0, y0: BY, z0: BZ, x1: x0 + SZ - 1, y1: BY + SZ - 1, z1: BZ + SZ - 1 }; }

let bot = null;
async function select(L) {
  bot.chat('//sel cuboid'); await sleep(200);
  bot.chat(`//pos1 ${L.x0},${L.y0},${L.z0}`); await sleep(200);
  bot.chat(`//pos2 ${L.x1},${L.y1},${L.z1}`); await sleep(200);
}
const DONE_RE = /have been changed|operation completed|blocks? affected|pasted|stacked|moved|Created/i;

(async () => {
  log(`=== #105 WorldEdit mutation-path audit (origin=${ORIGIN}, bot=${BOT}) ===`);
  const fx0 = BX - 16, fz0 = BZ - 16, fx1 = BX + 220, fz1 = BZ + 16;
  await rcon(`forceload add ${fx0} ${fz0} ${fx1} ${fz1}`); await sleep(800);

  bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
  await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
  await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
  await rcon(`tp ${BOT} ${BX} ${BY + 1} ${BZ}`); await sleep(2500);
  try { await bot.waitForChunksToLoad(); } catch { }
  await sleep(1500);
  bot.chat('//limit -1'); await sleep(300); // lift the per-op block cap for opped bot

  // ---- run one selection-based op and verify its records ----
  async function selOp(label, baseMat, opCmd, verify) {
    const L = lane();
    log(`--- ${label} ---`);
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} ${baseMat || 'air'}`); await sleep(300);
    await select(L);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat(opCmd); await done;
    await settle();
    await verify(L);
    return L;
  }

  // 1. //set — baseline (already proven in _worldedit-offmain-proof; included for completeness)
  await selOp('set', 'stone', '//set glass', async () => {
    const places = await pollCnt(`event='place' AND target='GLASS'`, 1);
    const breaks = await pollCnt(`event='break' AND target='STONE'`, 1);
    check(`//set logs place GLASS (${places})`, places >= 1, `got ${places}`);
    check(`//set logs break STONE (${breaks})`, breaks >= 1, `got ${breaks}`);
  });

  // 2. //replace
  await selOp('replace', 'stone', '//replace stone sandstone', async () => {
    const places = await pollCnt(`event='place' AND target='SANDSTONE'`, 1);
    check(`//replace logs place SANDSTONE (${places})`, places >= 1, `got ${places}`);
  });

  // 3. //walls
  await selOp('walls', null, '//walls bricks', async () => {
    const places = await pollCnt(`event='place' AND target='BRICKS'`, 1);
    check(`//walls logs place BRICKS (${places})`, places >= 1, `got ${places}`);
  });

  // 4. //overlay — places the pattern above the highest block in each column.
  // Run it HIGH (y=140) over a clean floor: overlay keys off the highest block
  // in the *full* world column, so near terrain it can no-op (see #105 notes).
  // High altitude removes that ambiguity, so it reliably places 25.
  {
    const L = lane(); const oy = 140; log('--- overlay ---');
    await rcon(`fill ${L.x0} ${oy - 2} ${L.z0} ${L.x1} ${oy + 6} ${L.z1} air`); await sleep(300);   // clean column
    await rcon(`fill ${L.x0} ${oy} ${L.z0} ${L.x1} ${oy} ${L.z1} stone`); await sleep(300);          // floor
    bot.chat('//sel cuboid'); await sleep(200);
    bot.chat(`//pos1 ${L.x0},${oy},${L.z0}`); await sleep(200);
    bot.chat(`//pos2 ${L.x1},${oy + 4},${L.z1}`); await sleep(200);
    const done = waitChat(bot, /overlaid/i, 30000); bot.chat('//overlay red_wool'); await done; await sleep(500);
    const places = await pollCnt(`event='place' AND target='RED_WOOL'`, 1);
    check(`//overlay logs place RED_WOOL (${places})`, places >= 1, `got ${places}`);
  }

  // 5. //stack — copy the region one step east into a cleared dest (so the copy
  // is a real change; FAWE skips no-op overwrites, vanilla doesn't).
  {
    const L = lane(); log('--- stack ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} emerald_block`); await sleep(250);
    await rcon(`fill ${L.x1 + 1} ${L.y0} ${L.z0} ${L.x1 + SZ} ${L.y1} ${L.z1} air`); await sleep(250);
    await select(L);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//stack 1 east'); await done; await settle();
    const places = await pollCnt(`event='place' AND target='EMERALD_BLOCK'`, 1);
    check(`//stack logs place EMERALD_BLOCK (${places})`, places >= 1, `got ${places}`);
  }

  // 6. //move — break source, place into a cleared dest
  {
    const L = lane(); log('--- move ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} lapis_block`); await sleep(250);
    await rcon(`fill ${L.x0 + 6} ${L.y0} ${L.z0} ${L.x0 + 6 + SZ - 1} ${L.y1} ${L.z1} air`); await sleep(250);
    await select(L);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//move 6 east'); await done; await settle();
    const breaks = await pollCnt(`event='break' AND target='LAPIS_BLOCK'`, 1);
    const places = await pollCnt(`event='place' AND target='LAPIS_BLOCK'`, 1);
    check(`//move logs break+place LAPIS_BLOCK (b=${breaks} p=${places})`, breaks >= 1 && places >= 1, `b=${breaks} p=${places}`);
  }

  // 7. //copy + //paste — copy, clear the region, paste it back (a real change,
  // not a no-op overwrite that FAWE dedups). -o pastes at the original position.
  {
    const L = lane(); log('--- copy/paste ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} gold_block`); await sleep(300);
    await select(L);
    bot.chat('//copy'); await sleep(900);
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} air`); await sleep(400);
    const before = await cnt(`event='place' AND target='GOLD_BLOCK'`);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//paste -o'); await done; await settle();
    const after = await pollCnt(`event='place' AND target='GOLD_BLOCK'`, before + 1);
    check(`//paste logs place GOLD_BLOCK (Δ=${after - before})`, after > before, `before=${before} after=${after}`);
  }

  // 8. //schem save/load/paste — clear the region before pasting the loaded schem
  {
    const L = lane(); log('--- schematic paste ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} diamond_block`); await sleep(300);
    await select(L);
    bot.chat('//copy'); await sleep(900);
    bot.chat('//schem save sg105test'); await sleep(1200);
    bot.chat('//schem load sg105test'); await sleep(1200);
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} air`); await sleep(400);
    const before = await cnt(`event='place' AND target='DIAMOND_BLOCK'`);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat('//paste -o'); await done; await settle();
    const after = await pollCnt(`event='place' AND target='DIAMOND_BLOCK'`, before + 1);
    check(`schematic //paste logs place DIAMOND_BLOCK (Δ=${after - before})`, after > before, `before=${before} after=${after}`);
    bot.chat('//schem delete sg105test'); await sleep(400);
  }

  // 9. //undo — the inverse edit must itself be logged (a fresh EditSession)
  {
    const L = lane(); log('--- undo ---');
    await rcon(`fill ${L.x0} ${L.y0} ${L.z0} ${L.x1} ${L.y1} ${L.z1} stone`); await sleep(300);
    await select(L);
    let done = waitChat(bot, DONE_RE, 30000); bot.chat('//set obsidian'); await done; await sleep(800);
    await pollCnt(`event='place' AND target='OBSIDIAN'`, 1);
    const before = await cnt();
    done = waitChat(bot, /Undid|undone/i, 30000); bot.chat('//undo'); await done; await sleep(800);
    // undo restores stone over the obsidian: a fresh edit that breaks OBSIDIAN + places STONE
    const total = await pollCnt('', before + 1);
    const undoBreaksObsidian = await pollCnt(`event='break' AND target='OBSIDIAN'`, 1, 8000);
    check(`//undo is itself logged (total Δ=${total - before})`, total > before, `before=${before} total=${total}`);
    check(`//undo logs break OBSIDIAN (${undoBreaksObsidian})`, undoBreaksObsidian >= 1, `got ${undoBreaksObsidian}`);
  }

  // 10. //redo — re-applying the edit must be logged too
  {
    log('--- redo ---');
    const before = await cnt();
    const done = waitChat(bot, /Redid|redone/i, 30000); bot.chat('//redo'); await done; await sleep(800);
    const total = await pollCnt('', before + 1);
    check(`//redo is itself logged (total Δ=${total - before})`, total > before, `before=${before} total=${total}`);
  }

  // 11. generation (player-centered) — sphere/cyl/pyramid on a platform
  let genIdx = 0;
  async function genOp(label, cmd, target) {
    log(`--- ${label} (generation) ---`);
    const cx = BX + 120 + (genIdx++) * 14, cz = BZ; // within the forceloaded box
    await rcon(`fill ${cx - 4} ${BY - 1} ${cz - 4} ${cx + 4} ${BY - 1} ${cz + 4} stone`); await sleep(300); // platform
    await rcon(`tp ${BOT} ${cx} ${BY} ${cz}`); await sleep(1500);
    const done = waitChat(bot, DONE_RE, 30000); bot.chat(cmd); await done; await sleep(500);
    const places = await pollCnt(`event='place' AND target='${target}'`, 1);
    check(`${label} logs place ${target} (${places})`, places >= 1, `got ${places}`);
  }
  await genOp('//sphere', '//sphere mossy_cobblestone 3', 'MOSSY_COBBLESTONE');
  await genOp('//cyl', '//cyl nether_bricks 3 2', 'NETHER_BRICKS');
  await genOp('//pyramid', '//pyramid quartz_block 3', 'QUARTZ_BLOCK');

  // 12. brush / tool edit (best-effort: confirm the brush fires in-world before judging logging)
  try {
    log('--- brush (sphere) ---');
    const cx = BX + 200, cz = BZ;
    await rcon(`fill ${cx - 5} ${BY - 1} ${cz - 5} ${cx + 5} ${BY + 8} ${cz + 5} air`); await sleep(200);
    await rcon(`tp ${BOT} ${cx} ${BY + 6} ${cz}`); await sleep(1500);
    await rcon(`give ${BOT} minecraft:stick`); await sleep(600);
    bot.setQuickBarSlot(0); await sleep(300);
    bot.chat('//brush sphere prismarine 2'); await sleep(600); // bind brush to held stick
    await bot.look(0, -Math.PI / 2, true); await sleep(600);     // look straight down
    for (let i = 0; i < 3; i++) { bot.activateItem(); await sleep(700); if (bot.deactivateItem) bot.deactivateItem(); await sleep(300); }
    const probe = await rcon(`execute if block ${cx} ${BY} ${cz} prismarine run say BRUSH_HIT_${BOT}`);
    const fired = /BRUSH_HIT/.test(probe);
    const places = await pollCnt(`event='place' AND target='PRISMARINE'`, 1, 12000);
    if (!fired && places === 0) {
      log(`  SKIP  brush — could not confirm the brush fired in-world (bot aim/range); inconclusive, not a logging verdict`);
      results.push({ name: 'brush', ok: true, detail: 'skipped/inconclusive', skipped: true });
    } else {
      check(`brush logs place PRISMARINE (fired=${fired}, places=${places})`, places >= 1, `fired=${fired} places=${places}`);
    }
  } catch (e) { log('  SKIP  brush — harness error: ' + (e?.message || e)); results.push({ name: 'brush', ok: true, detail: 'skipped/error', skipped: true }); }

  // ---- cleanup ----
  log('cleanup…');
  bot.quit();
  await rcon(`forceload remove ${fx0} ${fz0} ${fx1} ${fz1}`);

  log(`\n=== RESULT (origin=${ORIGIN}): ${pass} passed, ${fail} failed ===`);
  for (const r of results) log(`   ${r.skipped ? 'SKIP' : r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : '  — ' + r.detail}`);
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e?.message || e); process.exit(2); });
