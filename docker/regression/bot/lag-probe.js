// Does Spyglass lag the main thread? Measure MSPT idle vs during a large
// staff WorldEdit edit (the realistic heavy-recording moment), + GC.
import mineflayer from 'mineflayer';
import net from 'net';
const sleep = ms => new Promise(r => setTimeout(r, ms));
function pkt(i, t, b) { const u = Buffer.from(b); const l = 10 + u.length; const o = Buffer.alloc(4 + l); o.writeInt32LE(l, 0); o.writeInt32LE(i, 4); o.writeInt32LE(t, 8); u.copy(o, 12); return o; }
function rcon(cmd) { return new Promise((res, rej) => { const s = net.createConnection({ host: "127.0.0.1", port: 25576, timeout: 30000 }); let st = 0, bs = []; s.on("error", rej); s.on("timeout", () => { s.destroy(); rej("t/o"); }); s.on("connect", () => s.write(pkt(1, 3, "test123"))); s.on("data", c => { bs.push(c); const a = Buffer.concat(bs); if (a.length < 4) return; const l = a.readInt32LE(0); if (a.length < l + 4) return; if (st === 0) { st = 1; bs = []; s.write(pkt(1, 2, cmd)); } else { s.end(); res(a.slice(12, 12 + l - 10).toString().replace(/§./g, "")); } }); }); }
const mspt = async () => (await rcon("mspt")).replace(/\n/g, " ").trim();

(async () => {
  console.log("IDLE mspt:", await mspt());
  await sleep(800);
  console.log("IDLE tps :", (await rcon("tps")).replace(/\n/g, " ").trim());

  const X = 21000, Y = 80, Z = 21000, S = 64; // 64^3 = 262,144 blocks
  await rcon(`forceload add ${X} ${Z} ${X + S - 1} ${Z + S - 1}`); await sleep(800);
  for (let y = Y; y < Y + S; y += 8) await rcon(`fill ${X} ${y} ${Z} ${X + S - 1} ${Math.min(y + 7, Y + S - 1)} ${Z + S - 1} stone`);

  const bot = mineflayer.createBot({ host: "127.0.0.1", port: 25566, username: "lagprobe", version: "1.21.4" });
  await new Promise((r, j) => { bot.once("spawn", r); bot.once("error", j); });
  await rcon("op lagprobe"); await rcon("gamemode creative lagprobe"); await rcon(`tp lagprobe ${X} ${Y + S + 5} ${Z}`); await sleep(2500);
  bot.chat("//sel cuboid"); await sleep(300); bot.chat(`//pos1 ${X},${Y},${Z}`); await sleep(300); bot.chat(`//pos2 ${X + S - 1},${Y + S - 1},${Z + S - 1}`); await sleep(300);

  console.log(`\n-- bot //set air over 262,144 blocks (logged by Spyglass + CoreProtect) --`);
  bot.chat("//set air");
  for (let i = 0; i < 8; i++) { await sleep(1000); console.log(`  +${i + 1}s:`, await mspt()); }

  bot.quit(); await sleep(500);
  await rcon(`fill ${X} ${Y} ${Z} ${X + S - 1} ${Y + S - 1} ${Z + S - 1} air`); await rcon(`forceload remove ${X} ${Z} ${X + S - 1} ${Z + S - 1}`);
  process.exit(0);
})().catch(e => { console.log("ERR", e?.message || e); process.exit(1); });
