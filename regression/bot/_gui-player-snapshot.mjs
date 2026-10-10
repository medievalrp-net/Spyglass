// Isolated Minecraft 26.x server probe: the player-snapshot layout. Armor
// (boots..helmet) and offhand on the top row, main inventory in rows 1-3,
// hotbar in row 4, info book in the corners, and takes giving the pristine
// item (without the display-only slot label). Requires
// snapshot.players.enabled=true and interval=1s on the test server. Same
// setup as _26.3-gui.mjs. Survival mode.
import mineflayer from "mineflayer";
import { rcon, sleep, itemCount } from "./cases/lib.js";

const name = "ps" + Date.now().toString(36).slice(-5);
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
bot.on("kicked", (r) => {
  console.error("KICK", r);
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
const at = (w, slot) => (w.slots[slot] ? `${w.slots[slot].name}x${w.slots[slot].count}` : "empty");

try {
  await new Promise((res, rej) => {
    bot.once("spawn", res);
    bot.once("error", rej);
  });
  bot.physicsEnabled = false;
  await rcon(`op ${name}`);
  await rcon("difficulty peaceful"); // survival at spawn: no mobs mid-test
  await rcon(`gamemode survival ${name}`);
  await rcon(`clear ${name}`);
  await rcon(`item replace entity ${name} armor.feet with iron_boots`);
  await rcon(`item replace entity ${name} armor.head with diamond_helmet`);
  await rcon(`item replace entity ${name} weapon.offhand with shield`);
  await rcon(`item replace entity ${name} hotbar.0 with bread 5`);
  await rcon(`item replace entity ${name} inventory.0 with arrow 16`);
  await sleep(4000); // player snapshots capture every second on the test server
  await rcon(`clear ${name}`);
  await sleep(500);

  const w = await windowAfter(() => bot.chat(`/sg snapshot p:${name} t:1s`), "player snapshot");
  console.log("LAYOUT", [0, 1, 2, 3, 5, 8, 9, 36, 53].map((s) => `${s}:${at(w, s)}`).join(" "));
  ck(w.inventoryStart === 54, "player snapshot is 6 rows");
  ck(w.slots[0]?.name === "iron_boots", "boots render top-left");
  ck(w.slots[3]?.name === "diamond_helmet", "helmet renders in the fourth armor cell");
  ck(w.slots[1]?.name === "gray_stained_glass_pane" && w.slots[2]?.name === "gray_stained_glass_pane",
    "empty leggings and chestplate cells show placeholders");
  ck(w.slots[5]?.name === "shield", "offhand renders in its cell");
  ck(w.slots[8]?.name === "book" && w.slots[53]?.name === "book", "info book in both right corners");
  ck(w.slots[9]?.name === "arrow" && w.slots[9].count === 16, "main inventory slot 9 renders in row 1");
  ck(w.slots[36]?.name === "bread" && w.slots[36].count === 5, "hotbar slot 0 renders in row 4");

  await click(3, 0, 0); // helmet
  await sleep(1200);
  await click(36, 0, 0); // bread
  await sleep(1200);
  await click(1, 0, 0); // placeholder
  await click(8, 0, 0); // info
  await sleep(1200);
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  await sleep(1000);
  ck((await itemCount(name, "diamond_helmet")) === 1, "helmet take copies one helmet");
  ck((await itemCount(name, "bread")) === 5, "hotbar take copies five bread");
  ck((await itemCount(name)) === 6, "placeholder and info cells give nothing");
  // The menu labels armor cells with an extra lore line; the copy must not carry it.
  const helmet = await rcon(`data get entity ${name} Inventory[{id:"minecraft:diamond_helmet"}]`);
  console.log("HELMET", helmet);
  ck(helmet.includes("diamond_helmet") && !/lore/i.test(helmet), "the copied helmet carries no display label");

  console.log(failures === 0 ? "GUI PLAYER SNAPSHOT PROBE PASS" : `GUI PLAYER SNAPSHOT PROBE FAIL (${failures})`);
  bot.quit();
  process.exit(failures === 0 ? 0 : 1);
} catch (e) {
  console.error(e);
  bot.quit();
  process.exit(1);
}
