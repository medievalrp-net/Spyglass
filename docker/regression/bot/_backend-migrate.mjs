// Isolated Minecraft 26.x server probe: `/sg migrate <MIGRATE_TARGET>` as an
// in-game operator, waiting for the completion line. Prints
// "MIGRATED <records>" on success. Set MIGRATE_CONFIRM=1 to pass --confirm.
// Same connection setup as _26.3-gui.mjs.
import mineflayer from "mineflayer";
import { rcon, sleep } from "./cases/lib.js";

const target = process.env.MIGRATE_TARGET;
const name = "mg" + Date.now().toString(36).slice(-5);
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
try {
  await new Promise((res, rej) => {
    bot.once("spawn", res);
    bot.once("error", rej);
  });
  bot.physicsEnabled = false;
  await rcon(`op ${name}`);
  await sleep(800);
  const done = new Promise((res) => {
    const t = setTimeout(() => res(null), 600000);
    bot.on("messagestr", (s) => {
      console.log("CHAT", s);
      if (/Migration complete|Migration failed|not configured|already contains|nothing to migrate|already running/i.test(s)) {
        clearTimeout(t);
        res(s);
      }
    });
  });
  bot.chat(`/sg migrate ${target}${process.env.MIGRATE_CONFIRM ? " --confirm" : ""}`);
  const line = await done;
  const m = line && line.match(/Migration complete: ([\d,]+) records/);
  if (m) {
    console.log("PASS migrated to", target);
    console.log("MIGRATED", m[1].replace(/,/g, ""));
  } else {
    console.log("FAIL migration to", target, ":", line || "(no completion within 10 minutes)");
  }
  bot.quit();
  process.exit(m ? 0 : 1);
} catch (e) {
  console.error(e);
  bot.quit();
  process.exit(1);
}
