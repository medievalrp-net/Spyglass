import mineflayer from 'mineflayer';
import net from 'net';
const HOST='127.0.0.1', PORT=25566, RCON_PORT=25576, PASS='test123', BOT='rolltest';
const sleep = ms => new Promise(r => setTimeout(r, ms));
function p(id, t, body){const b=Buffer.from(body,'utf8');const len=4+4+b.length+2;const o=Buffer.alloc(4+len);o.writeInt32LE(len,0);o.writeInt32LE(id,4);o.writeInt32LE(t,8);b.copy(o,12);o.writeInt16LE(0,12+b.length);return o;}
function rcon(cmd){return new Promise((res,rej)=>{const s=net.createConnection(RCON_PORT,HOST);let st=0;const bufs=[];s.on('error',rej);s.on('connect',()=>s.write(p(0x1337,3,PASS)));s.on('data',c=>{bufs.push(c);const all=Buffer.concat(bufs);if(all.length<4)return;const len=all.readInt32LE(0);if(all.length<len+4)return;const id=all.readInt32LE(4);const body=all.slice(12,12+len-10).toString('utf8');if(st===0){if(id===-1)return rej('auth');st=1;bufs.length=0;s.write(p(0x1337,2,cmd));}else{s.end();res(body);}});});}
const bot = mineflayer.createBot({host:HOST,port:PORT,username:BOT,version:'1.21.4'});
await new Promise((r,j)=>{bot.once('spawn',r);bot.once('error',j);});
await rcon(`op ${BOT}`); await rcon(`gamemode creative ${BOT}`); await sleep(500);
await rcon(`forceload add 11000 11000 11005 11005`);
await rcon(`tp ${BOT} 11000.5 100 11000.5`);
await sleep(2000);
await rcon(`fill 11000 80 11000 11005 89 11005 stone`);
await sleep(500);
bot.chat(`//sel cuboid`); await sleep(150);
bot.chat(`//pos1 11000,80,11000`); await sleep(150);
bot.chat(`//pos2 11005,89,11005`); await sleep(150);
bot.chat(`//replace stone air`);
await sleep(2000);
console.log('replace done. issuing rollback...');
bot.chat(`/spyglass rollback p:${BOT} t:1m -g`);
await sleep(200);
console.log('rollback issued. resume dir:');
process.exit(0);
