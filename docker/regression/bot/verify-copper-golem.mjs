// Isolated fixture only: genuine vanilla AI transfer, with a bridged client keeping the arena active.
import mineflayer from "mineflayer";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { rcon, sleep, sgOp, blockIs } from "./cases/lib.js";
const name = "cg" + Date.now().toString(36).slice(-6);
const bot = mineflayer.createBot({ host: process.env.SG_HOST || "127.0.0.1", port: +process.env.SG_PORT,
  username: name, version: "1.21.8", auth: "offline" });
const write = bot._client.write.bind(bot._client);
bot._client.write = (name, packet) => {
  if (!["position", "position_look", "look", "flying"].includes(name)) write(name, packet);
};
try {
  await new Promise((resolve, reject) => { bot.once("spawn", resolve); bot.once("error", reject); });
  for (const command of [
    `gamemode creative ${name}`, "kill @e[tag=sg_copper_probe]",
    "fill 98 79 98 108 79 106 stone", "fill 98 80 98 108 85 106 air",
    "setblock 100 80 100 copper_chest[facing=north]", "setblock 104 80 100 chest[facing=north]",
    "item replace block 100 80 100 container.0 with diamond 32",
    "item replace block 104 80 100 container.0 with diamond 1",
    `tp ${name} 102.5 80 104.5`,
    'summon copper_golem 102.5 80 100.5 {Tags:["sg_copper_probe"],PersistenceRequired:1b}'
  ]) console.log(command, await rcon(command));
  let transferred = false;
  for (let i = 0; i < 30; i++) {
    await sleep(2000);
    const contents = await rcon("data get block 104 80 100 Items");
    if (/count:\s*(?:17|33)/.test(contents)) { console.log("PASS vanilla copper golem delivered diamonds", contents); transferred = true; break; }
  }
  if (!transferred) {
    console.log(await rcon("data get entity @e[tag=sg_copper_probe,limit=1]"));
    throw new Error("Copper golem did not complete its vanilla transfer");
  }
  await sleep(2000);
  if (process.env.SG_LOGGING_PROBE === "true") {
    console.log(await rcon(`loggingprobe ${name}`));
    let result = "RUNNING";
    for (let i=0;i<45 && result==="RUNNING";i++) {
      await sleep(1000);
      result = await readFile(path.join(process.env.SG_SERVER_DIR,"logging-probe-result.txt"),"utf8").catch(()=>"RUNNING");
    }
    if(result!=="PASS") throw new Error("Logging integration: "+result);
    console.log("PASS native logging interactions; persisted rows checked by runner");
    await rcon(`op ${name}`);
    if (process.env.SG_MINECRAFT_TARGET === "26.3") {
      const rollback = await sgOp(bot,"rollback",`p:${name} a:cushion-break t:2m r:20 --entities`,15000);
      if(rollback.applied!==1) throw new Error("Cushion rollback: "+rollback.line);
      let state = await rcon("loggingprobe state");
      if(!state.includes("CUSHIONS 1 [RED]")) throw new Error("Cushion state: "+state);
      const undo = await sgOp(bot,"undo","",15000);
      if(undo.applied!==1 || !(await rcon("loggingprobe state")).includes("CUSHIONS 0")) throw new Error("Cushion undo failed");
      console.log("PASS cushion rollback restores red state; undo removes exactly one");
      const bed = await sgOp(bot,"rollback",`p:${name} a:straw-bed-consume t:2m r:20`,15000);
      if(bed.applied!==2 || !(await blockIs(116,80,102,"straw_bed[occupied=false]"))
          || !(await blockIs(116,80,103,"straw_bed[occupied=false]"))) throw new Error("Straw-bed rollback failed");
      const bedUndo = await sgOp(bot,"undo","",15000);
      if(bedUndo.applied!==2 || !(await blockIs(116,80,102,"air")) || !(await blockIs(116,80,103,"air"))) throw new Error("Straw-bed undo failed");
      console.log("PASS straw-bed rollback and undo preserve both halves without a phantom sleeper");
    }

  }
} catch (error) { console.error("FAIL", error); process.exitCode = 1; }
finally { await rcon("kill @e[tag=sg_copper_probe]").catch(() => {}); bot.quit(); }
