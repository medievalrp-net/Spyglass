// Isolated Minecraft 26.x server probe: the history _backend-core.mjs
// (PHASE=before) wrote is searchable on the current backend and still drives
// a rollback and undo - for checking a backend that received the history by
// /sg migrate. Reads the bot name and chat tag from STATE_FILE. Salvage, undo
// history and wand state are operational and deliberately not migrated, so
// they are not checked here. Same setup as _26.3-gui.mjs.
import fs from "fs";
import mineflayer from "mineflayer";
import { rcon, sleep, sgOp, blockIs, chatCollect } from "./cases/lib.js";

const state = JSON.parse(fs.readFileSync(process.env.STATE_FILE, "utf8"));
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
bot.on("messagestr", (s) => console.log("CHAT", s));
const search = async (query) => {
  const lines = await chatCollect(bot, `/sg search ${query}`);
  const events = lines.filter((l) => l.startsWith("="))
    .reduce((sum, l) => sum + Number(l.match(/ x(\d+)/)?.[1] || 1), 0);
  return { lines, events };
};
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
try {
  await new Promise((res, rej) => {
    bot.once("spawn", res);
    bot.once("error", rej);
  });
  bot.physicsEnabled = false;
  await rcon(`op ${name}`);
  await rcon(`tp ${name} 112.5 80 108.5`);
  await sleep(1000);
  let r = await search(`a:say p:${name} t:6h -g`);
  ck(r.lines.some((l) => l.includes(state.tag)), "migrated chat line is searchable");
  r = await search("a:place b:stone t:6h r:20");
  ck(r.events >= 72, `migrated WorldEdit placements are searchable (${r.events} events)`);
  r = await search(`a:command p:${name} t:6h -g`);
  ck(r.events >= 1, `migrated command records are searchable (${r.events})`);
  const before = await countBlocks("stone");
  const op = await sgOp(bot, "rollback", "a:place b:stone t:6h r:20");
  ck(op.applied >= 72 && (await countBlocks("air")) === 72, `rollback from migrated history works (${op.line})`);
  await sgOp(bot, "undo", "");
  ck((await countBlocks("stone")) === before, "undo restores it");
  console.log(failures === 0 ? "BACKEND HISTORY PASS" : `BACKEND HISTORY FAIL (${failures})`);
  bot.quit();
  process.exit(failures === 0 ? 0 : 1);
} catch (e) {
  console.error(e);
  bot.quit();
  process.exit(1);
}
