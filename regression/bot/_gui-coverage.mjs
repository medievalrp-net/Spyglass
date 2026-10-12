// Isolated Minecraft 26.x server probe for the menu paths _26.3-gui.mjs and
// _gui-exploits.mjs leave out: two staff withdrawing from one salvage at
// once, salvage pagination (50 containers), a double-chest (6-row) snapshot,
// and a salvage take with a full inventory. Same setup as _26.3-gui.mjs:
// WorldEdit, ViaVersion/ViaBackwards bridge, movement packets suppressed.
// Survival mode. Uses the area x 60-85, z 60-75 around y 80.
import mineflayer from "mineflayer";
import { rcon, sleep, sgOp, blockIs, itemCount } from "./cases/lib.js";

const tag = Date.now().toString(36).slice(-5);
let failures = 0;
const ck = (v, s) => {
  console.log(v ? "PASS" : "FAIL", s);
  if (!v) failures++;
};

const TRICKS = [
  ["shift-click", 0, 1],
  ["number-key swap", 0, 2],
  ["offhand swap", 40, 2],
  ["drop one (Q)", 0, 4],
  ["drop stack (ctrl-Q)", 1, 4],
  ["double-click collect", 0, 6],
  ["middle-click clone", 2, 3],
  ["right-click", 1, 0],
  ["left-click", 0, 0],
];

function makeBot(name) {
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
    console.log(`CHAT[${name}]`, s);
  });
  bot.on("kicked", (r) => console.error(`KICK[${name}]`, r));
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
  console.log(`WINDOW[${bot.username}]`, JSON.stringify(w.title));
  return w;
};
const title = (w) => JSON.stringify(w?.title ?? "");
const content = (w, from = 0, to = 45) => w.slots.slice(from, to).filter(Boolean);
const inventory = async (name) => {
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
const closeAll = async (...bots) => {
  for (const bot of bots) if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  await sleep(1000);
};
const clearArea = async () => {
  await rcon("fill 60 80 60 85 83 75 air");
  await rcon("kill @e[type=item]");
};
const weSet = async (from, to, block) => {
  await rcon(`//pos1 ${from}`);
  await rcon(`//pos2 ${to}`);
  await rcon(`//set ${block}`);
};
// Root -> first rollback -> first container. Returns the item-level window.
const openFirstSalvage = async (bot) => {
  await windowAfter(bot, () => bot.chat("/sg inventory"), "salvage rollbacks");
  await windowAfter(bot, () => click(bot, 0, 0, 0), "salvage containers");
  return windowAfter(bot, () => click(bot, 0, 0, 0), "salvage items");
};
const salvageIsEmpty = async (bot) => {
  bot.chatLog.length = 0;
  bot.chat("/sg inventory");
  await sleep(2000);
  return !bot.currentWindow && bot.chatLog.some((s) => s.includes("No salvaged inventories"));
};

let a;
let b;
try {
  a = await makeBot("ca" + tag);
  b = await makeBot("cb" + tag);
  for (const bot of [a, b]) {
    await rcon(`op ${bot.username}`);
    await rcon(`gamemode survival ${bot.username}`);
    await rcon(`clear ${bot.username}`);
  }
  await rcon("forceload add 60 60 85 75");
  await rcon("fill 60 79 60 85 79 75 stone");
  await clearArea();
  await rcon(`tp ${a.username} 80.5 80 70.5`);
  await rcon(`tp ${b.username} 81.5 80 70.5`);
  await sleep(1000);
  await rcon("//world world");

  // ---- two staff, one salvage, simultaneous click bursts ----
  await weSet("80,80,72", "80,80,72", "chest");
  await rcon("item replace block 80 80 72 container.0 with diamond 64");
  await rcon("item replace block 80 80 72 container.1 with emerald 64");
  await rcon("item replace block 80 80 72 container.2 with gold_ingot 32");
  await sleep(3000);
  let rb = await sgOp(a, "rollback", "a:place b:chest t:2m r:3 --containers");
  console.log("RB", rb);
  ck(await blockIs(80, 80, 72, "air"), "two staff: rollback removes chest");
  for (const bot of [a, b]) {
    const w = await openFirstSalvage(bot);
    ck(content(w).length === 3, `two staff: ${bot.username} sees three stacks`);
  }
  for (const [, button, mode] of TRICKS) {
    for (const slot of [0, 1, 2]) {
      click(a, slot, button, mode);
      click(b, slot, button, mode);
    }
  }
  await sleep(4000);
  await closeAll(a, b);
  // A click while another take on the container is in flight is refused by
  // design, so a burst can leave stacks behind. Collect them with slow single
  // clicks; the count below then checks both no duplicates and nothing lost.
  for (let i = 0; i < 6; i++) {
    try {
      await openFirstSalvage(a);
    } catch {
      break; // no salvage window: nothing left
    }
    await click(a, 0, 0, 0);
    await sleep(2500);
    await closeAll(a);
  }
  {
    const ia = await inventory(a.username);
    const ib = await inventory(b.username);
    const sum = (id) => (ia[id] || 0) + (ib[id] || 0);
    console.log("INVENTORY", a.username, JSON.stringify(ia), b.username, JSON.stringify(ib));
    ck(
      sum("diamond") === 64 && sum("emerald") === 64 && sum("gold_ingot") === 32 &&
        total(ia) + total(ib) === 160 && (await groundItems()) === 0,
      "two staff: each stack paid out exactly once between them",
    );
  }
  ck(await salvageIsEmpty(a), "two staff: salvage is empty afterwards");
  await sgOp(a, "undo", "");
  for (const bot of [a, b]) await rcon(`clear ${bot.username}`);

  // ---- pagination: one rollback over 50 containers ----
  await clearArea();
  await rcon(`tp ${a.username} 66.5 80 69.5`);
  await rcon(`tp ${b.username} 76.5 80 73.5`);
  await weSet("62,80,62", "71,80,66", "chest");
  for (let x = 62; x <= 71; x++) {
    for (let z = 62; z <= 66; z++) {
      await rcon(`item replace block ${x} 80 ${z} container.0 with diamond 1`);
    }
  }
  await sleep(3000);
  rb = await sgOp(a, "rollback", "a:place b:chest t:2m r:12 --containers");
  console.log("RB", rb);
  ck(rb.applied === 50, `pagination: rollback removes 50 chests (${rb.applied})`);
  await windowAfter(a, () => a.chat("/sg inventory"), "salvage rollbacks");
  let w = await windowAfter(a, () => click(a, 0, 0, 0), "salvage containers");
  ck(title(w).includes("50 container(s)"), "pagination: container list titled 50 container(s)");
  ck(content(w).length === 45, `pagination: page 1 shows 45 containers (${content(w).length})`);
  ck(
    w.slots[50]?.name === "arrow" && w.slots[48]?.name === "gray_stained_glass_pane",
    "pagination: page 1 offers next, not previous",
  );
  await click(a, 50, 0, 0);
  await sleep(1200);
  w = a.currentWindow;
  ck(content(w).length === 5, `pagination: page 2 shows the other 5 (${content(w).length})`);
  ck(
    w.slots[48]?.name === "arrow" && w.slots[50]?.name === "gray_stained_glass_pane",
    "pagination: page 2 offers previous, not next",
  );
  await click(a, 48, 0, 0);
  await sleep(1200);
  ck(content(a.currentWindow).length === 45, "pagination: previous returns to page 1");
  await click(a, 50, 0, 0);
  await sleep(1200);
  // Every trick on the page-2 nav row and empty content cells moves nothing.
  for (const [, button, mode] of TRICKS) {
    for (const slot of [46, 49, 50, 7, 30, 48]) await click(a, slot, button, mode);
  }
  await sleep(1000);
  ck(total(await inventory(a.username)) === 0 && (await groundItems()) === 0,
    "pagination: page-2 nav icons and empty cells give nothing");
  // The trick burst also paged around; land on page 2 again and open one.
  if (content(a.currentWindow).length !== 5) {
    await click(a, 50, 0, 0);
    await sleep(1200);
  }
  w = await windowAfter(a, () => click(a, 0, 0, 0), "page-2 container items");
  ck(w.slots[0]?.name === "diamond", "pagination: a page-2 container opens its items");
  await click(a, 0, 0, 0);
  await sleep(3000);
  ck((await inventory(a.username)).diamond === 1, "pagination: taking from it gives one diamond");
  w = a.currentWindow;
  ck(title(w).includes("49 container(s)"), `pagination: emptied container leaves 49 (${title(w)})`);
  await closeAll(a);
  await sgOp(a, "undo", "");
  await rcon(`clear ${a.username}`);

  // ---- double chest: 6-row snapshot, info in chat ----
  await clearArea();
  await rcon(`tp ${a.username} 80.5 80 66.5`);
  await rcon("setblock 80 80 64 chest[facing=north,type=left]");
  await rcon("setblock 81 80 64 chest[facing=north,type=right]");
  // Vanilla puts a double chest's RIGHT half first: its slot 0 is window
  // slot 0, and the left half's last slot is window slot 53 (the bottom row,
  // where a smaller snapshot's info row would sit).
  await rcon("item replace block 81 80 64 container.0 with diamond 3");
  await rcon("item replace block 80 80 64 container.26 with emerald 4");
  await sleep(3000);
  a.chatLog.length = 0;
  w = await windowAfter(a, () => a.chat("/sg snapshot trg:80,80,64 t:1s"), "double chest snapshot");
  console.log("SNAPSHOT inventoryStart", w.inventoryStart, "items", JSON.stringify(
    w.slots.slice(0, w.inventoryStart).map((s, i) => s && `${i}:${s.name}x${s.count}`).filter(Boolean)));
  ck(w.inventoryStart === 54, `double chest: snapshot is 6 rows (${w.inventoryStart} slots)`);
  ck(a.chatLog.some((s) => s.includes(" as of ")), "double chest: capture info goes to chat");
  ck(w.slots[0]?.name === "diamond" && w.slots[0].count === 3, "double chest: right half's first slot renders at the top");
  ck(w.slots[53]?.name === "emerald" && w.slots[53].count === 4, "double chest: left half's last slot renders in the bottom row");
  await click(a, 53, 0, 0);
  await sleep(1500);
  await closeAll(a);
  {
    const inv = await inventory(a.username);
    ck(inv.emerald === 4 && total(inv) === 4, `double chest: bottom-row take copies four emeralds (${JSON.stringify(inv)})`);
  }
  await rcon(`clear ${a.username}`);

  // ---- salvage take with a full inventory ----
  await clearArea();
  await rcon(`tp ${a.username} 84.5 80 71.5`);
  await weSet("84,80,73", "84,80,73", "chest");
  await rcon("item replace block 84 80 73 container.0 with diamond 5");
  await sleep(3000);
  rb = await sgOp(a, "rollback", "a:place b:chest t:2m r:2 --containers");
  console.log("RB", rb);
  await rcon(`give ${a.username} stone 2304`);
  await sleep(800);
  w = await openFirstSalvage(a);
  a.chatLog.length = 0;
  await click(a, 0, 0, 0);
  await sleep(1500);
  ck(a.chatLog.some((s) => s.includes("Your inventory is full")), "full inventory: take is refused with a message");
  ck(!(await inventory(a.username)).diamond, "full inventory: no diamonds given");
  await closeAll(a);
  await rcon(`clear ${a.username}`);
  await sleep(600);
  w = await openFirstSalvage(a);
  ck(w.slots[0]?.name === "diamond" && w.slots[0].count === 5, "full inventory: the stack is still in salvage");
  await click(a, 0, 0, 0);
  await sleep(2000);
  await closeAll(a);
  {
    const inv = await inventory(a.username);
    ck(inv.diamond === 5 && total(inv) === 5, "full inventory: after clearing, the take gives exactly five");
  }
  ck(await salvageIsEmpty(a), "full inventory: salvage is empty afterwards");
  await sgOp(a, "undo", "");

  console.log(failures === 0 ? "GUI COVERAGE PROBE PASS" : `GUI COVERAGE PROBE FAIL (${failures})`);
  a.quit();
  b.quit();
  process.exit(failures === 0 ? 0 : 1);
} catch (e) {
  console.error(e);
  a?.quit();
  b?.quit();
  process.exit(1);
}
