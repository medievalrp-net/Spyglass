import mineflayer from "mineflayer";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { rcon, sleep } from "./cases/lib.js";
const name = "tr" + Date.now().toString(36).slice(-7);
const bot = mineflayer.createBot({host:process.env.SG_HOST || "127.0.0.1", port:+process.env.SG_PORT,
 username:name, version:"1.21.8", auth:"offline"});
try {
 await new Promise((resolve,reject)=>{bot.once("spawn",resolve);bot.once("error",reject);});
 await rcon(`toolremovalprobe ${name}`);
 let result="RUNNING";
 for(let i=0;i<30 && result==="RUNNING";i++){
  await sleep(1000);
  result=await readFile(path.join(process.env.SG_SERVER_DIR,"tool-removal-result.txt"),"utf8");
 }
 console.log(result);
 if(!result.includes("PASS tool deactivation")) throw new Error(result);
} catch(error) { console.error(error); process.exitCode=1; }
finally {bot.quit();}
