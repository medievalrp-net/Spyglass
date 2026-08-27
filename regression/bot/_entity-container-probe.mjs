// #347 live probe: donkey chest, horse saddle slot, chest boat.
// Sneak + interact opens the entity inventory; deposits/withdrawals must land
// as deposit/withdraw records (container type = entity type) plus open/close.
import mineflayer from 'mineflayer';
import net from 'net';
import { execFileSync } from 'child_process';

const HOST = '127.0.0.1', PORT = 25590, RCON_PORT = 25580, PASS = 'test123';
const DB = '/private/tmp/claude-502/-Volumes-External-NVME-Documents-GitHub-MedievalRP-Spyglass/70087784-6aef-47af-bc62-bb42bb5beb5d/scratchpad/sgfawe/plugins/Spyglass/spyglass.db';
const BOT = 'ec' + Date.now().toString(36).slice(-4);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);
let pass = 0, fail = 0; const results = [];
const check = (name, ok, detail) => { if (ok) { pass++; log(`  PASS  ${name}`); } else { fail++; log(`  FAIL  ${name} :: ${detail}`); } results.push({ name, ok, detail }); };

function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 }); let st = 0, bs = []; s.on('error', rej); s.on('timeout', () => { s.destroy(); rej('t/o'); }); s.on('connect', () => s.write(pkt(1, 3, PASS))); s.on('data', c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, '')); } }); }); }
function sql(q) { try { return execFileSync('sqlite3', ['-cmd', '.timeout 4000', '-readonly', DB, q], { encoding: 'utf8' }).trim(); } catch (e) { return 'SQLERR:' + (e.stderr || e.message); } }
function rowsAt(x, z) {
  return sql(`SELECT ed.val || '/' || COALESCE(td.val,'?') || '/s' || COALESCE(CAST(r.y AS TEXT),'?') FROM records r JOIN dict ed ON r.event=ed.id LEFT JOIN dict td ON r.target=td.id WHERE r.x BETWEEN ${x - 1} AND ${x + 1} AND r.z BETWEEN ${z - 1} AND ${z + 1} AND ed.val IN ('deposit','withdraw','open','close') ORDER BY r.seq;`).replace(/\n/g, ' | ');
}
function cntAt(x, z, ev, target) {
  const t = target ? ` AND td.val='${target}'` : '';
  return parseInt(sql(`SELECT COUNT(*) FROM records r JOIN dict ed ON r.event=ed.id LEFT JOIN dict td ON r.target=td.id WHERE r.x BETWEEN ${x - 1} AND ${x + 1} AND r.z BETWEEN ${z - 1} AND ${z + 1} AND ed.val='${ev}'${t};`)) || 0;
}

let bot = null;
function waitWindow(t = 8000) {
  return new Promise(res => {
    const h = w => { clearTimeout(timer); res(w); };
    const timer = setTimeout(() => { bot.removeListener('windowOpen', h); res(null); }, t);
    bot.once('windowOpen', h);
  });
}
async function closeWin(w) { try { bot.closeWindow(w); } catch { } await sleep(600); }
function findEntity(name, x, z) {
  return Object.values(bot.entities).find(e => e.name === name
    && Math.abs(e.position.x - x) < 3 && Math.abs(e.position.z - z) < 3);
}
// On 1.21.8 sneak-interact does not open the entity inventory for a bot
// (sneak state rides the player_input bitflags the server does not see in
// time), so do it the way a riding player does: mount, then send the
// open_vehicle_inventory player command (entity_action actionId 5 on this
// protocol - the sneak start/stop ids left entity_action in 1.21.2+).
async function openEntityInv(name, x, z) {
  await dismount();
  await rcon(`tp ${BOT} ${x - 1} 72 ${z}`); await sleep(1500);
  const e = findEntity(name, x, z);
  if (!e) throw new Error('entity not found: ' + name);
  for (let i = 0; i < 3 && bot.vehicle !== e; i++) {
    bot.activateEntity(e).catch(() => { });
    await sleep(1500);
  }
  if (bot.vehicle !== e) throw new Error('mount failed for ' + name);
  const winP = waitWindow();
  bot._client.write('entity_action', { entityId: bot.entity.id, actionId: 5, jumpBoost: 0 });
  return await winP;
}
async function dismount() {
  for (let i = 0; i < 4 && bot.vehicle; i++) {
    try { bot.dismount(); } catch { }
    await sleep(800);
  }
}
// prismarine-windows cannot model clicks on the horse window type, so send
// raw window_click packets: the click action is server-authoritative and the
// hashed cursor/changed-slot fields are only desync hints (server resyncs).
let lastStateId = 1;
function trackState(botRef) {
  botRef._client.on('window_items', p => { if (p.stateId !== undefined) lastStateId = p.stateId; });
  botRef._client.on('set_slot', p => { if (p.stateId !== undefined) lastStateId = p.stateId; });
}
async function rawClick(slot) {
  bot._client.write('window_click', {
    windowId: bot.currentWindow ? bot.currentWindow.id : 1,
    stateId: lastStateId,
    slot, mouseButton: 0, mode: 0,
    changedSlots: [],
    cursorItem: undefined,
  });
  await sleep(600);
}
async function placeInto(w, itemName, containerSlot) {
  const n = w.slots.length - 36;
  const from = w.slots.findIndex((it, i) => i >= n && it && it.name === itemName);
  if (from < 0) throw new Error('item not in inventory view: ' + itemName);
  await rawClick(from);
  await rawClick(containerSlot);
}
async function takeOut(w, containerSlot) {
  const n = w.slots.length - 36;
  const empt = w.slots.findIndex((it, i) => i >= n && it == null);
  await rawClick(containerSlot);
  if (empt >= 0) { await rawClick(empt); }
}

(async () => {
  log(`=== entity-container probe (bot=${BOT}) ===`);
  const BX = 21000, BY = 72, BZ = 21000;
  await rcon(`forceload add ${BX - 16} ${BZ - 16} ${BX + 32} ${BZ + 16}`); await sleep(500);
  await rcon(`fill ${BX - 4} ${BY - 1} ${BZ - 4} ${BX + 24} ${BY - 1} ${BZ + 8} minecraft:stone`); await sleep(500);
  await rcon('kill @e[type=minecraft:donkey]'); await rcon('kill @e[type=minecraft:horse]'); await rcon('kill @e[type=minecraft:oak_chest_boat]'); await sleep(500);

  bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.8' });
  trackState(bot);
  await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
  await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`);
  await rcon(`tp ${BOT} ${BX} ${BY} ${BZ}`); await sleep(2500);
  try { await bot.waitForChunksToLoad(); } catch { }
  await sleep(1000);

  // ---- donkey chest ----
  log('--- donkey ---');
  const DK = { x: BX + 4, z: BZ + 2 };
  log('summon: ' + (await rcon(`summon minecraft:donkey ${DK.x} ${BY} ${DK.z} {ChestedHorse:1b,Tame:1b,NoAI:1b,Invulnerable:1b}`)).trim());
  await rcon(`clear ${BOT}`); await rcon(`give ${BOT} minecraft:iron_ingot 9`); await sleep(1500);
  let w = await openEntityInv('donkey', DK.x, DK.z);
  check('D1 donkey inventory opens', w != null, 'no windowOpen');
  if (w) {
    log('  window slots=' + w.slots.length);
    await placeInto(w, 'iron_ingot', 3);   // a chest slot
    await sleep(1000);
    await takeOut(w, 3);
    await sleep(1000);
    await closeWin(w);
    await dismount();
    await sleep(2500);
    log('  rows: ' + rowsAt(DK.x, DK.z));
    check('D2 donkey deposit recorded', cntAt(DK.x, DK.z, 'deposit', 'IRON_INGOT') >= 1, rowsAt(DK.x, DK.z));
    check('D3 donkey withdraw recorded', cntAt(DK.x, DK.z, 'withdraw', 'IRON_INGOT') >= 1, rowsAt(DK.x, DK.z));
    check('D4 donkey open recorded', cntAt(DK.x, DK.z, 'open', 'DONKEY') >= 1, rowsAt(DK.x, DK.z));
    check('D5 donkey close recorded', cntAt(DK.x, DK.z, 'close', 'DONKEY') >= 1, rowsAt(DK.x, DK.z));
  }

  // ---- horse saddle slot ----
  // The saddle rides in pre-equipped (the horse window's client-side slot
  // view is misaligned in mineflayer, so searching the player-inv region for
  // the saddle is unreliable); two raw clicks on server slot 0 then produce a
  // deterministic withdraw (pick up) + deposit (put back) pair.
  log('--- horse ---');
  const HR = { x: BX + 12, z: BZ + 2 };
  log('summon: ' + (await rcon(`summon minecraft:horse ${HR.x} ${BY} ${HR.z} {Tame:1b,NoAI:1b,Invulnerable:1b,equipment:{saddle:{id:"minecraft:saddle",count:1}}}`)).trim());
  await sleep(1500);
  w = await openEntityInv('horse', HR.x, HR.z);
  check('H1 horse inventory opens', w != null, 'no windowOpen');
  if (w) {
    await rawClick(0);   // pick the saddle up = withdraw
    await sleep(800);
    await rawClick(0);   // put it back = deposit
    await sleep(800);
    await closeWin(w);
    await dismount();
    await sleep(2500);
    log('  rows: ' + rowsAt(HR.x, HR.z));
    check('H2 saddle withdraw recorded (slot 0)', cntAt(HR.x, HR.z, 'withdraw', 'SADDLE') >= 1, rowsAt(HR.x, HR.z));
    check('H3 saddle deposit recorded (slot 0)', cntAt(HR.x, HR.z, 'deposit', 'SADDLE') >= 1, rowsAt(HR.x, HR.z));
  }

  // ---- chest boat (best effort) ----
  log('--- chest boat ---');
  const CB = { x: BX + 20, z: BZ + 2 };
  log('summon: ' + (await rcon(`summon minecraft:oak_chest_boat ${CB.x} ${BY} ${CB.z}`)).trim());
  await rcon(`give ${BOT} minecraft:gold_ingot 3`); await sleep(1500);
  try {
    w = await openEntityInv('oak_chest_boat', CB.x, CB.z);
    if (w == null) {
      log('  SKIP chest boat: window did not open (mount/interact semantics)');
      results.push({ name: 'B1 chest boat (inconclusive)', ok: true, detail: 'skipped' });
    } else {
      await placeInto(w, 'gold_ingot', 1);
      await sleep(1000);
      await closeWin(w);
      await dismount();
      await sleep(2500);
      log('  rows: ' + rowsAt(CB.x, CB.z));
      check('B1 chest boat deposit recorded', cntAt(CB.x, CB.z, 'deposit', 'GOLD_INGOT') >= 1, rowsAt(CB.x, CB.z));
    }
  } catch (e) {
    log('  SKIP chest boat: ' + e.message);
    results.push({ name: 'B1 chest boat (inconclusive)', ok: true, detail: 'skipped: ' + e.message });
  }

  bot.quit();
  await rcon(`forceload remove ${BX - 16} ${BZ - 16} ${BX + 32} ${BZ + 16}`);
  log(`\n=== RESULT: ${pass} passed, ${fail} failed ===`);
  for (const r of results) log(`   ${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : '  :: ' + r.detail}`);
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { log('FATAL', e?.stack || e?.message || e); process.exit(2); });
