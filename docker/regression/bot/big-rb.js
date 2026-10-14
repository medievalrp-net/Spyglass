import mineflayer from 'mineflayer';
import net from 'net';
const HOST='127.0.0.1', PORT=25566, RCON_PORT=25576, PASS='test123', BOT='rolltest';
const sleep = ms => new Promise(r => setTimeout(r, ms));
function p(id, t, body){const b=Buffer.from(body,'utf8');const len=4+4+b.length+2;const o=Buffer.alloc(4+len);o.writeInt32LE(len,0);o.writeInt32LE(id,4);o.writeInt32LE(t,8);b.copy(o,12);o.writeInt16LE(0,12+b.length);return o;}
function rcon(cmd){return new Promise((res,rej)=>{const s=net.createConnection(RCON_PORT,HOST);let st=0;const bufs=[];s.on('error',rej);s.on('connect',()=>s.write(p(0x1337,3,PASS)));s.on('data',c=>{bufs.push(c);const all=Buffer.concat(bufs);if(all.length<4)return;const len=all.readInt32LE(0);if(all.length<len+4)return;const id=all.readInt32LE(4);const body=all.slice(12,12+len-10).toString('utf8');if(st===0){if(id===-1)return rej('auth');st=1;bufs.length=0;s.write(p(0x1337,2,cmd));}else{s.end();res(body);}});});}
const bot = mineflayer.createBot({host:HOST,port:PORT,username:BOT,version:'1.21.4'});
await new Promise((r,j)=>{bot.once('spawn',r);bot.once('error',j);});
await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`); await sleep(500);
const SIDE = 50; const x0=12000, z0=12000, y0=80;
const x1=x0+SIDE-1, y1=y0+SIDE-1, z1=z0+SIDE-1;
await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
await rcon(`tp ${BOT} ${x0+0.5} ${y1+5} ${z0+0.5}`);
await sleep(2000);
const FILL = 32768; const slab = Math.max(1, Math.floor(FILL/(SIDE*SIDE)));
for (let y=y0; y<=y1; y+=slab) {
  await rcon(`fill ${x0} ${y} ${z0} ${x1} ${Math.min(y1,y+slab-1)} ${z1} stone`);
}
await sleep(1000);
bot.chat(`//sel cuboid`); await sleep(200);
bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(200);
bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(200);
bot.chat(`//replace stone air`);
await sleep(8000);  // wait for replace + drain
console.log('replace done.');
console.log('issuing rollback...');
bot.chat(`/spyglass rollback p:${BOT} t:5m -g`);
await sleep(200);
process.exit(0);
