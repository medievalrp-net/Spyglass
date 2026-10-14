// Isolated Minecraft 26.x server probe for a storage backend: the core
// record -> search -> rollback -> restore -> undo loop, chat logging, and
// persistence across a full server restart. Run it twice against one server
// and backend:
//   PHASE=before  writes and checks, then leaves known data behind
//                 (a tagged chat line, a 72-block WorldEdit edit, an
//                 unwithdrawn salvage, captured player snapshots)
//   PHASE=after   after a server restart: that data is still searchable,
//                 the salvage still pays out exactly once, the player snapshot
//                 still opens, and a rollback of pre-restart edits still works
// STATE_FILE (JSON) carries the bot name and tag from before to after.
// Same setup as _26.3-gui.mjs (WorldEdit, ViaVersion/ViaBackwards bridge,
// movement packets suppressed), plus snapshot.players.enabled=true with a 1s
// interval. Uses x 100-130, z 100-130 around y 80.
import fs from "fs";
import mineflayer from "mineflayer";
import { Vec3 } from "vec3";
import { rcon, sleep, sgOp, blockIs, itemCount, chatCollect, grep } from "./cases/lib.js";

const PHASE = process.env.PHASE || "before";
const STATE_FILE = process.env.STATE_FILE || "/tmp/sg-backend-core.json";
const state = PHASE === "after" ? JSON.parse(fs.readFileSync(STATE_FILE, "utf8"))
  : { name: "bc" + Date.now().toString(36).slice(-5), tag: "sgtag" + Date.now().toString(36) };
const name = state.name;
let failures = 0;
const ck = (v, s) => {
  console.log(v ? "PASS" : "FAIL", s);
  if (!v) failures++;
};

const bot = mineflayer.createBot({
  host: process.env.SG_HOST || "127.0.0.1",
  port: +process.env.SG_PORT,
  username: name,
  version: "1.21.8",
  auth: "offline",
});
const write = bot._client.write.bind(bot._client);
bot._client.write = (n, p) => {
  if (!["position", "position_look", "look", "flying"].includes(n)) write(n, p);
};
bot.chatLog = [];
bot.on("messagestr", (s) => {
  bot.chatLog.push(s);
  console.log("CHAT", s);
});
bot.on("kicked", (r) => {
  console.error("KICK", JSON.stringify(r));
  process.exit(2);
});
const click = (slot, button, mode) => bot.clickWindow(slot, button, mode).catch(() => {});
const windowAfter = async (action, label) => {
  const p = new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error(`window timeout: ${label}`)), 15000);
    bot.once("windowOpen", (w) => {
      clearTimeout(t);
      res(w);
    });
  });
  await action();
  const w = await p;
  await sleep(700);
  return w;
};
const search = async (query) => {
  const lines = await chatCollect(bot, `/sg search ${query}`);
  const header = lines.map((l) => l.match(/(\d[\d,]*)\s+results?/i)).find(Boolean);
  // Search groups identical events into one line ("placed STONE x72"), so
  // the event total is the sum of each result line's xN.
  const events = lines.filter((l) => l.startsWith("="))
    .reduce((sum, l) => sum + Number(l.match(/ x(\d+)/)?.[1] || 1), 0);
  return { lines, count: header ? parseInt(header[1].replace(/,/g, ""), 10) : 0, events };
};
// The WorldEdit region: 6 x 6 x 2 = 72 cells.
const REGION = { x0: 110, x1: 115, y0: 80, y1: 81, z0: 110, z1: 115 };
const cells = [];
for (let x = REGION.x0; x <= REGION.x1; x++)
  for (let y = REGION.y0; y <= REGION.y1; y++)
    for (let z = REGION.z0; z <= REGION.z1; z++) cells.push([x, y, z]);
const countBlocks = async (block) => {
  let n = 0;
  for (const c of cells) if (await blockIs(...c, block)) n++;
  return n;
};
const CHEST = [104, 80, 104];

try {
  await new Promise((res, rej) => {
    bot.once("spawn", res);
    bot.once("error", rej);
  });
  bot.physicsEnabled = false;
  await rcon(`op ${name}`);
  await rcon(`gamemode survival ${name}`);
  await rcon(`tp ${name} 112.5 80 108.5`);
  await sleep(1000);

  if (PHASE === "before") {
    await rcon(`clear ${name}`);
    await rcon("forceload add 100 100 130 130");
    await rcon("fill 100 79 100 130 79 130 stone");
    await rcon("fill 100 80 100 130 84 130 air");
    await rcon("kill @e[type=item]");
    await rcon("//world world");

    // ---- chat and command logging ----
    bot.chat(`${state.tag} hello from the backend probe`);
    await sleep(2500);
    let r = await search(`a:say p:${name} t:5m -g`);
    ck(r.lines.some((l) => l.includes(state.tag)), `chat line is logged and searchable (${r.count} result(s))`);
    r = await search(`a:command p:${name} t:5m -g`);
    ck(r.count >= 1, `commands are logged (${r.count} result(s))`);

    // ---- WorldEdit edit: search, rollback, restore, undo ----
    await rcon(`//pos1 ${REGION.x0},${REGION.y0},${REGION.z0}`);
    await rcon(`//pos2 ${REGION.x1},${REGION.y1},${REGION.z1}`);
    await rcon("//set stone");
    await sleep(4000);
    ck((await countBlocks("stone")) === 72, "WorldEdit placed 72 stone");
    r = await search("a:place b:stone t:5m r:20");
    ck(r.events >= 72, `search finds the 72 placements (${r.events} events)`);
    let op = await sgOp(bot, "rollback", "a:place b:stone t:5m r:20");
    ck(op.applied >= 72 && (await countBlocks("air")) === 72, `rollback clears all 72 (${op.line})`);
    op = await sgOp(bot, "restore", "a:place b:stone t:5m r:20");
    ck(op.applied >= 72 && (await countBlocks("stone")) === 72, `restore puts all 72 back (${op.line})`);
    op = await sgOp(bot, "undo", "");
    ck((await countBlocks("air")) === 72, `undo reverses the restore (${op.line})`);
    // Leave the edit in place (logged again) for the after-restart rollback.
    await rcon("//set stone");
    await sleep(4000);
    ck((await countBlocks("stone")) === 72, "the 72-block edit is left in place for after the restart");

    // ---- real player place and break through the bridge (best effort) ----
    await rcon(`gamemode creative ${name}`);
    await rcon(`tp ${name} 120.5 80 120.5`);
    await rcon("setblock 122 80 120 stone");
    await sleep(1000);
    let realActions = "n/a";
    try {
      const target = bot.blockAt(new Vec3(122, 80, 120));
      if (target && target.name === "stone") {
        await Promise.race([bot.dig(target, true), sleep(8000).then(() => { throw new Error("dig timeout"); })]);
        await sleep(1500);
        realActions = (await blockIs(122, 80, 120, "air")) ? "dug" : "dig not applied";
      }
    } catch (e) {
      realActions = `dig failed: ${e.message}`;
    }
    if (realActions === "dug") {
      r = await search(`a:break p:${name} t:5m r:10`);
      ck(r.count >= 1, `a real player break is logged (${r.count})`);
    } else {
      console.log("N/A real player block actions through the bridge:", realActions);
    }
    await rcon(`gamemode survival ${name}`);
    await rcon(`tp ${name} 112.5 80 108.5`);

    // ---- leave an unwithdrawn salvage behind ----
    await rcon(`//pos1 ${CHEST.join(",")}`);
    await rcon(`//pos2 ${CHEST.join(",")}`);
    await rcon("//set chest");
    await rcon(`item replace block ${CHEST.join(" ")} container.0 with emerald 3`);
    await sleep(3000);
    op = await sgOp(bot, "rollback", "a:place b:chest t:5m r:20 --containers");
    ck(await blockIs(...CHEST, "air"), `chest rollback creates a salvage (${op.line})`);

    // ---- player snapshots: give a known kit and let captures run ----
    await rcon(`clear ${name}`);
    await rcon(`give ${name} lapis_lazuli 9`);
    await sleep(4000);
    state.snapAt = Date.now(); // a capture with the lapis exists by now

    fs.writeFileSync(STATE_FILE, JSON.stringify(state));
  } else {
    // ---- after a full server restart ----
    let r = await search(`a:say p:${name} t:2h -g`);
    ck(r.lines.some((l) => l.includes(state.tag)), "chat line survives the restart");
    r = await search("a:place b:stone t:2h r:20");
    ck(r.events >= 72, `WorldEdit placements survive the restart (${r.events} events)`);
    ck(await blockIs(...CHEST, "air"), "the salvaged chest is still gone");

    await rcon(`clear ${name}`);
    await sleep(500);
    await windowAfter(() => bot.chat("/sg inventory"), "salvage rollbacks");
    await windowAfter(() => click(0, 0, 0), "salvage containers");
    let w = await windowAfter(() => click(0, 0, 0), "salvage items");
    ck(w.slots[0]?.name === "emerald" && w.slots[0].count === 3, "salvage survives the restart");
    for (let i = 0; i < 3; i++) click(0, 0, 0);
    await sleep(2500);
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
    await sleep(800);
    ck((await itemCount(name, "emerald")) === 3 && (await itemCount(name)) === 3,
      "the restored salvage pays out exactly once");

    await rcon(`clear ${name}`);
    await sleep(500);
    // As of the moment the before phase confirmed the lapis capture.
    const ago = Math.max(1, Math.floor((Date.now() - state.snapAt) / 1000));
    w = await windowAfter(() => bot.chat(`/sg snapshot p:${name} t:${ago}s`), "player snapshot");
    const lapis = w.slots.slice(0, w.inventoryStart).findIndex((s) => s?.name === "lapis_lazuli");
    ck(lapis >= 0 && w.slots[lapis].count === 9, "player snapshot captured before the restart still opens");
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow);

    const op = await sgOp(bot, "rollback", "a:place b:stone t:2h r:20");
    ck(op.applied >= 72 && (await countBlocks("air")) === 72, `rollback of pre-restart edits works (${op.line})`);
    await sgOp(bot, "undo", "");
    ck((await countBlocks("stone")) === 72, "and undo puts them back");
  }

  console.log(failures === 0 ? `BACKEND CORE ${PHASE} PASS` : `BACKEND CORE ${PHASE} FAIL (${failures})`);
  bot.quit();
  process.exit(failures === 0 ? 0 : 1);
} catch (e) {
  console.error(e);
  bot.quit();
  process.exit(1);
}
