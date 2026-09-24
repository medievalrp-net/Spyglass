// Isolated 26.3 server probe. Requires WorldEdit; older bot transport via ViaBackwards.
// All movement is server teleportation because the bridge rejects bot movement packets.
import mineflayer from "mineflayer";
import { rcon, sleep, sgOp, blockIs } from "./cases/lib.js";
const name = "ui" + Date.now().toString(36).slice(-5);
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
const ck = (v, s) => {
  console.log(v ? "PASS" : "FAIL", s);
  if (!v) throw new Error(s);
};
const windowAfter = async (action) => {
  const p = new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error("window timeout")), 15000);
    bot.once("windowOpen", (w) => {
      clearTimeout(t);
      res(w);
    });
  });
  await action();
  const w = await p;
  await sleep(500);
  console.log("WINDOW", w.title);
  return w;
};
const count = async () => {
  const data = await rcon(`data get entity ${name} Inventory`);
  console.log("INVENTORY", data);
  return [...data.matchAll(/\{[^{}]*\}/g)]
    .filter((m) => m[0].includes('id: "minecraft:diamond"'))
    .reduce(
      (sum, m) => sum + Number(m[0].match(/count:\s*(\d+)/)?.[1] || 0),
      0,
    );
};
try {
  await new Promise((res, rej) => {
    bot.once("spawn", res);
    bot.once("error", rej);
  });
  bot.physicsEnabled = false;
  await rcon(`op ${name}`);
  await rcon(`gamemode creative ${name}`);
  await rcon("forceload add 64 64");
  await rcon("fill 64 79 64 70 79 70 stone");
  await rcon("fill 64 80 64 70 83 70 air");
  await rcon(`tp ${name} 66.5 80 66.5`);
  await sleep(1000);
  await rcon("//world world");
  await rcon("//pos1 65,80,65");
  await rcon("//pos2 65,80,65");
  await rcon("//set chest");
  await sleep(1000);
  await rcon("item replace block 65 80 65 container.0 with diamond 7");
  await sleep(4000);
  let w = await windowAfter(() => bot.chat("/sg snapshot trg:65,80,65 t:1s"));
  ck(
    w.slots[0]?.name === "diamond" && w.slots[0].count === 7,
    "snapshot renders seven diamonds",
  );
  await rcon(`deop ${name}`);
  await bot.clickWindow(0, 0, 0);
  await sleep(800);
  ck((await count()) === 0, "snapshot denies take after permission removal");
  await rcon(`op ${name}`);
  await rcon(`give ${name} stone 2304`);
  await sleep(800);
  await bot.clickWindow(0, 0, 0);
  await sleep(800);
  ck(
    (await count()) === 0,
    "snapshot refuses whole stack when inventory is full",
  );
  await rcon(`clear ${name}`);
  await sleep(500);
  const before = await count();
  await bot.clickWindow(0, 0, 0);
  await sleep(1800);
  ck((await count()) === before + 7, "snapshot click copies seven diamonds");
  bot.closeWindow(bot.currentWindow);
  await sleep(600);
  await rcon(`clear ${name}`);
  await sleep(500);
  const rb = await sgOp(
    bot,
    "rollback",
    "a:place b:chest t:60s r:5 --containers",
  );
  console.log("RB", rb);
  ck(await blockIs(65, 80, 65, "air"), "rollback removes chest");
  w = await windowAfter(() => bot.chat("/sg inventory"));
  ck(w.slots[0]?.name === "chest_minecart", "salvage rollback browser opens");
  w = await windowAfter(() => bot.clickWindow(0, 0, 0));
  ck(w.slots[0] !== null, "salvage container browser opens");
  w = await windowAfter(() => bot.clickWindow(0, 0, 0));
  ck(
    w.slots[0]?.name === "diamond" && w.slots[0].count === 7,
    "salvage item browser renders seven diamonds",
  );
  await bot.clickWindow(0, 0, 0);
  await sleep(1800);
  ck((await count()) === 7, "salvage withdraw gives exactly seven diamonds");
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  await sleep(500);
  bot.chat("/sg inventory");
  await sleep(1500);
  ck((await count()) === 7, "reopening empty salvage gives no duplicates");
  await sgOp(bot, "undo", "");
  ck(await blockIs(65, 80, 65, "chest"), "undo restores chest");
  console.log("GUI PROBE PASS");
  bot.quit();
  process.exit(0);
} catch (e) {
  console.error(e);
  bot.quit();
  process.exit(1);
}
