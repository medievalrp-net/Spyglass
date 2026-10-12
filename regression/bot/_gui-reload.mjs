// Isolated Minecraft 26.x server probe: reload the server (`reload confirm`)
// while a salvage item menu is open. Once Spyglass disables, nothing cancels
// clicks in its menus any more, so the menu must close with the plugin. The
// probe tries to take the displayed stack across the reload, then checks the
// salvage survived and still pays out exactly once. Same setup as
// _26.3-gui.mjs. Survival mode. Uses the area x 60-85, z 60-75 around y 80.
import mineflayer from "mineflayer";
import { rcon, sleep, sgOp, blockIs, itemCount } from "./cases/lib.js";

const name = "rl" + Date.now().toString(36).slice(-5);
let failures = 0;
const ck = (v, s) => {
  console.log(v ? "PASS" : "FAIL", s);
  if (!v) failures++;
};
const TRICKS = [[0, 1], [0, 2], [40, 2], [0, 4], [1, 4], [0, 6], [2, 3], [1, 0], [0, 0]];

function makeBot() {
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
  bot.gone = false;
  bot.on("messagestr", (s) => {
    bot.chatLog.push(s);
    console.log("CHAT", s);
  });
  bot.on("kicked", (r) => console.error("KICK", r));
  bot.on("end", () => {
    bot.gone = true;
    console.log("DISCONNECTED");
  });
  return new Promise((res, rej) => {
    bot.once("spawn", () => {
      bot.physicsEnabled = false;
      res(bot);
    });
    bot.once("error", rej);
  });
}
const click = (bot, slot, button, mode) =>
  bot.clickWindow(slot, button, mode).catch(() => {});
const windowAfter = async (bot, action, label) => {
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
  console.log("WINDOW", JSON.stringify(w.title));
  return w;
};
const inventory = async () => {
  // Exact server-side counts; "*" is every item the player holds.
  const counts = { "*": await itemCount(name) };
  for (const id of ["diamond", "emerald", "gold_ingot", "stone", "arrow", "paper", "gray_stained_glass_pane", "chest_minecart", "chest", "book", "barrier"]) {
    const n = await itemCount(name, id);
    if (n) counts[id] = n;
  }
  return counts;
};
const total = (inv) => inv["*"];
const groundItems = async () => {
  const r = await rcon("execute positioned 72 80 67 if entity @e[type=item,distance=..40]");
  return Number(r.match(/count:\s*(\d+)/)?.[1] || 0);
};
const openFirstSalvage = async (bot) => {
  await windowAfter(bot, () => bot.chat("/sg inventory"), "salvage rollbacks");
  await windowAfter(bot, () => click(bot, 0, 0, 0), "salvage containers");
  return windowAfter(bot, () => click(bot, 0, 0, 0), "salvage items");
};

let bot;
try {
  bot = await makeBot();
  await rcon(`op ${name}`);
  await rcon(`gamemode survival ${name}`);
  await rcon(`clear ${name}`);
  await rcon("forceload add 60 60 85 75");
  await rcon("fill 60 79 60 85 79 75 stone");
  await rcon("fill 60 80 60 85 83 75 air");
  await rcon("kill @e[type=item]");
  await rcon(`tp ${name} 84.5 80 71.5`);
  await sleep(1000);
  await rcon("//world world");
  await rcon("//pos1 84,80,73");
  await rcon("//pos2 84,80,73");
  await rcon("//set chest");
  await rcon("item replace block 84 80 73 container.0 with diamond 5");
  await sleep(3000);
  const rb = await sgOp(bot, "rollback", "a:place b:chest t:2m r:2 --containers");
  console.log("RB", rb);
  ck(await blockIs(84, 80, 73, "air"), "rollback removes chest");
  let w = await openFirstSalvage(bot);
  ck(w.slots[0]?.name === "diamond" && w.slots[0].count === 5, "salvage item menu shows five diamonds");

  let closed = false;
  bot.once("windowClose", () => {
    closed = true;
    console.log("WINDOW CLOSED BY SERVER");
  });
  // Bukkit's plugin reload (plain `reload` is vanilla's data-pack reload).
  // RCON answers once the reload has finished on the main thread.
  const reply = String(await rcon("bukkit:reload confirm").catch((e) => `rcon: ${e.message}`));
  console.log("RELOAD", reply.slice(0, 300));
  ck(/reload complete/i.test(reply), "server reloaded its plugins");
  await sleep(3000);
  if (bot.gone) {
    console.log("reconnecting after reload");
    bot = await makeBot();
  } else {
    ck(closed, "reload closes the open Spyglass menu");
    // If the old menu were still open, nothing would cancel its clicks any
    // more: try to take the displayed stack with every click mode.
    if (bot.currentWindow) {
      for (const [button, mode] of TRICKS) await click(bot, 0, button, mode);
      await sleep(1000);
    }
  }
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  await sleep(1000);
  {
    const inv = await inventory();
    const ground = await groundItems();
    console.log("INVENTORY", JSON.stringify(inv), "GROUND", ground);
    ck(!inv.diamond && ground === 0, "nothing taken from the menu across the reload");
    ck(total(inv) === 0, "no menu icons in the inventory after the reload");
  }
  // ViaVersion does not survive a reload: connections it bridged before the
  // reload break on their next packet. Reconnect before using the menus again.
  bot.quit();
  await sleep(2000);
  bot = await makeBot();
  w = await openFirstSalvage(bot);
  ck(w.slots[0]?.name === "diamond" && w.slots[0].count === 5, "salvage survives the reload intact");
  await click(bot, 0, 0, 0);
  await sleep(2000);
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  await sleep(1000);
  {
    const inv = await inventory();
    ck(inv.diamond === 5 && total(inv) === 5, `post-reload take gives exactly five (${JSON.stringify(inv)})`);
  }
  await sgOp(bot, "undo", "");

  console.log(failures === 0 ? "GUI RELOAD PROBE PASS" : `GUI RELOAD PROBE FAIL (${failures})`);
  bot.quit();
  process.exit(failures === 0 ? 0 : 1);
} catch (e) {
  console.error(e);
  bot?.quit();
  process.exit(1);
}
