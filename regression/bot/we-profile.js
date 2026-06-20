// Profile the main thread during a bulk WorldEdit //set, to attribute how much
// tick time Spyglass's WE-edit logging costs. Compares old (synchronous) vs new
// (off-main) jars: grep the Sundial report for WorldEditSubscriber frames.
// Usage: node we-profile.js <tag>   (tag distinguishes runs, e.g. old / new)
import mineflayer from 'mineflayer';
import net from 'net';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const TAG = process.argv[2] || 'run';
function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: "127.0.0.1", port: 25576, timeout: 60000 }); let st = 0, bs = []; s.on("error", rej); s.on("timeout", () => { s.destroy(); rej("t/o"); }); s.on("connect", () => s.write(pkt(1, 3, "test123"))); s.on("data", c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, "")); } }); }); }
function waitChat(bot, re, t) { return new Promise(r => { const h = m => { if (re.test(m)) { bot.removeListener('messagestr', h); r(m); } }; bot.on('messagestr', h); setTimeout(() => { bot.removeListener('messagestr', h); r(null); }, t); }); }

const X = 18000, Y = 70, Z = 18000, S = 100; // 100^3 = 1,000,000 blocks

(async () => {
  console.log(`=== WE main-thread profile [${TAG}] : //set air over ${S}^3 = ${(S**3).toLocaleString()} blocks ===`);
  await rcon(`forceload add ${X} ${Z} ${X + S - 1} ${Z + S - 1}`); await sleep(800);
  console.log('fill stone…');
  for (let y = Y; y < Y + S; y += 3) await rcon(`fill ${X} ${y} ${Z} ${X + S - 1} ${Math.min(y + 2, Y + S - 1)} ${Z + S - 1} stone`);
  await sleep(1500);

  const bot = mineflayer.createBot({ host: "127.0.0.1", port: 25566, username: "weprof", version: "1.21.4" });
  await new Promise((r, j) => { bot.once("spawn", r); bot.once("error", j); });
  await rcon("op weprof"); await rcon("gamemode creative weprof"); await rcon(`tp weprof ${X} ${Y + S + 5} ${Z}`); await sleep(2500);
  bot.chat("//sel cuboid"); await sleep(300); bot.chat(`//pos1 ${X},${Y},${Z}`); await sleep(300); bot.chat(`//pos2 ${X + S - 1},${Y + S - 1},${Z + S - 1}`); await sleep(400);

  console.log('sundial start main…');
  await rcon('sundial start main 30');
  await sleep(500);
  const done = waitChat(bot, /operation completed|blocks have been changed/i, 120000);
  const t0 = Date.now();
  bot.chat('//set air');
  await done;
  const setMs = Date.now() - t0;
  console.log(`//set air done in ${setMs}ms`);
  await sleep(800);
  await rcon('sundial stop');
  await sleep(2500);

  bot.quit();
  await rcon(`fill ${X} ${Y} ${Z} ${X + S - 1} ${Y + S - 1} ${Z + S - 1} air`);
  await rcon(`forceload remove ${X} ${Z} ${X + S - 1} ${Z + S - 1}`);
  console.log(`set-wallclock-ms=${setMs}`);
  process.exit(0);
})().catch(e => { console.log("ERR", e?.message || e); process.exit(1); });
