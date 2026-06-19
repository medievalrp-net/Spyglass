// End-to-end proof for #87: WorldEdit edit-logging moved off the main thread.
// A //set over stone + a filled chest must still log break/place records
// (origin=worldedit) and roll back faithfully — including the chest's items,
// which are captured on the main thread and must survive the off-main build.
import mineflayer from 'mineflayer';
import vec3 from 'vec3';
import net from 'net';

const HOST = '127.0.0.1', PORT = 25566, RCON_PORT = 25576, PASS = 'test123';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);
const BOT = 'wep' + Math.floor(1000 + Math.random() * 8999); // unique → CH rows never collide

function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 60000 }); let st = 0, bs = []; s.on('error', rej); s.on('timeout', () => { s.destroy(); rej('t/o'); }); s.on('connect', () => s.write(pkt(1, 3, PASS))); s.on('data', c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, '')); } }); }); }
function waitChat(bot, re, t) { return new Promise(r => { const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); r(m); } }; bot.on('messagestr', h); setTimeout(() => { bot.removeListener('messagestr', h); r(null); }, t); }); }
async function ch(q) { return (await (await fetch(`http://localhost:8123/?query=${encodeURIComponent(q)}`)).text()).trim(); }

let pass = 0, fail = 0;
const check = (name, ok, detail) => { if (ok) { pass++; log(`  ✅ ${name}`); } else { fail++; log(`  ❌ ${name} — ${detail}`); } };

// Small region so the test is fast; the off-main behaviour is identical at any
// size (scale is proven separately by the profiler run).
const S = 6, X = 16000, Y = 75, Z = 16000;
const x1 = X + S - 1, y1 = Y + S - 1, z1 = Z + S - 1;
const CX = X + 3, CY = Y + 3, CZ = Z + 3;       // chest cell inside the region
const VOL = S * S * S;                          // 216 cells

(async () => {
  log(`=== #87 WorldEdit off-main logging proof (bot=${BOT}) ===`);
  await rcon(`forceload add ${X} ${Z} ${x1} ${z1}`); await sleep(600);

  log('setup: fill stone + a chest of 17 diamonds…');
  await rcon(`fill ${X} ${Y} ${Z} ${x1} ${y1} ${z1} stone`); await sleep(400);
  await rcon(`setblock ${CX} ${CY} ${CZ} chest`); await sleep(200);
  await rcon(`data merge block ${CX} ${CY} ${CZ} {Items:[{Slot:0b,id:"minecraft:diamond",count:17}]}`); await sleep(300);
  const pre = await rcon(`data get block ${CX} ${CY} ${CZ} Items`);
  check('setup chest holds 17 diamonds', /diamond/.test(pre) && /17/.test(pre), pre.slice(0, 80));

  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: '1.21.4' });
  await new Promise((r, j) => { bot.once('spawn', r); bot.once('error', j); });
  await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`); await rcon(`tp ${BOT} ${CX} ${y1 + 3} ${CZ}`); await sleep(2500);
  try { await bot.waitForChunksToLoad(); } catch { }
  await sleep(1500);

  log('//set glass over the region (break + place, off-main build)…');
  bot.chat('//sel cuboid'); await sleep(300);
  bot.chat(`//pos1 ${X},${Y},${Z}`); await sleep(300);
  bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(300);
  const setDone = waitChat(bot, /have been changed|operation completed/i, 60000);
  bot.chat('//set glass');
  await setDone;

  log('draining to ClickHouse…');
  await sleep(8000);
  const breaks = parseInt(await ch(`SELECT count() FROM spyglass.event_records WHERE origin_kind='worldedit' AND source_player_name='${BOT}' AND event='break'`)) || 0;
  const places = parseInt(await ch(`SELECT count() FROM spyglass.event_records WHERE origin_kind='worldedit' AND source_player_name='${BOT}' AND event='place'`)) || 0;
  const glassPlaces = parseInt(await ch(`SELECT count() FROM spyglass.event_records WHERE origin_kind='worldedit' AND source_player_name='${BOT}' AND event='place' AND target='GLASS'`)) || 0;
  const chestBreaks = parseInt(await ch(`SELECT count() FROM spyglass.event_records WHERE origin_kind='worldedit' AND source_player_name='${BOT}' AND event='break' AND target='CHEST'`)) || 0;
  check(`break records logged off-main (${breaks}/${VOL})`, breaks === VOL, `got ${breaks}`);
  check(`place records logged off-main (${places}/${VOL})`, places === VOL, `got ${places}`);
  check(`edit applied — all places are glass (${glassPlaces}/${VOL})`, glassPlaces === VOL, `got ${glassPlaces}`);
  check(`destroyed chest captured (target=CHEST: ${chestBreaks})`, chestBreaks === 1, `got ${chestBreaks}`);

  log(`rollback: /sg rollback p:${BOT} t:10m -g`);
  await rcon(`sg rollback p:${BOT} t:10m -g`);
  for (let i = 0; i < 60; i++) { const q = await rcon('sg rbqueue list'); if (/Recent/.test(q) && !/Running:/.test(q)) break; await sleep(1000); }
  await sleep(1500);

  const cornerName = bot.blockAt(vec3(X, Y, Z))?.name;
  const chestName = bot.blockAt(vec3(CX, CY, CZ))?.name;
  check('region restored to stone', cornerName === 'stone', cornerName);
  check('chest block restored', chestName === 'chest', chestName);
  const post = await rcon(`data get block ${CX} ${CY} ${CZ} Items`);
  check('chest items restored (17 diamonds survived off-main capture+rollback)',
    /diamond/.test(post) && /17/.test(post), post.slice(0, 100));

  bot.quit();
  await rcon(`fill ${X} ${Y} ${Z} ${x1} ${y1} ${z1} air`);
  await rcon(`forceload remove ${X} ${Z} ${x1} ${z1}`);
  log(`\n=== RESULT: ${pass} passed, ${fail} failed ===`);
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { log('FATAL', e?.message || e); process.exit(2); });
