// One-shot 1M block scale test to find the upper limit.
import mineflayer from 'mineflayer';
import net from 'net';
const HOST='127.0.0.1', PORT=25566, RCON_PORT=25576, RCON_PASS='test123', BOT_NAME='rolltest';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ts = () => '['+new Date().toISOString().slice(11,19)+']';
const log = (...a) => console.log(ts(),...a);
function packet(id,type,body){const b=Buffer.from(body,'utf8');const len=4+4+b.length+2;const o=Buffer.alloc(4+len);o.writeInt32LE(len,0);o.writeInt32LE(id,4);o.writeInt32LE(type,8);b.copy(o,12);o.writeInt16LE(0,12+b.length);return o;}
function rcon(cmd){return new Promise((resolve,reject)=>{const s=net.createConnection(RCON_PORT,HOST);let stage=0;const buffers=[];s.on('error',reject);s.on('connect',()=>s.write(packet(0x1337,3,RCON_PASS)));s.on('data',c=>{buffers.push(c);const all=Buffer.concat(buffers);if(all.length<4)return;const len=all.readInt32LE(0);if(all.length<len+4)return;const id=all.readInt32LE(4);const body=all.slice(12,12+len-10).toString('utf8');if(stage===0){if(id===-1)return reject(new Error('auth'));stage=1;buffers.length=0;s.write(packet(0x1337,2,cmd));}else{s.end();resolve(body);}});});}
function waitForChat(bot,re,timeout){return new Promise(r=>{const h=m=>{if(re.test(m)){bot.removeListener('messagestr',h);r(m);}};bot.on('messagestr',h);setTimeout(()=>{bot.removeListener('messagestr',h);r(null);},timeout);});}

(async () => {
    const bot = mineflayer.createBot({host:HOST,port:PORT,username:BOT_NAME,version:'1.21.4'});
    await new Promise((res,rej)=>{bot.once('spawn',res);bot.once('error',rej);});
    bot.on('messagestr',m=>{if(/(reversal|skip|reason|error|exception|fail|querying|spyglass)/i.test(m)){log('chat>',m.slice(0,200));}});
    await rcon(`op ${BOT_NAME}`); await rcon(`gamemode creative ${BOT_NAME}`); await sleep(500);

    const SIDE = 100;  // 1,000,000 cells
    const x0=3000, y0=80, z0=3000;
    const x1=x0+SIDE-1, y1=y0+SIDE-1, z1=z0+SIDE-1;
    log(`1M: cube ${SIDE}^3 at (${x0},${y0},${z0})-(${x1},${y1},${z1})`);

    await rcon(`forceload add ${x0} ${z0} ${x1} ${z1}`);
    await rcon(`tp ${BOT_NAME} ${x0+0.5} ${y1+5} ${z0+0.5}`);
    await sleep(3000);

    log('filling stone...');
    const FILL_CAP = 32768;
    const slab = Math.max(1, Math.floor(FILL_CAP/(SIDE*SIDE)));
    for (let y=y0; y<=y1; y+=slab) {
        const yEnd = Math.min(y1, y+slab-1);
        await rcon(`fill ${x0} ${y} ${z0} ${x1} ${yEnd} ${z1} stone`);
    }
    await sleep(1000);

    log('//replace stone air ...');
    bot.chat(`//sel cuboid`); await sleep(200);
    bot.chat(`//pos1 ${x0},${y0},${z0}`); await sleep(200);
    bot.chat(`//pos2 ${x1},${y1},${z1}`); await sleep(200);
    const replaceDone = waitForChat(bot, /blocks have been replaced/i, 600000);
    const t0 = Date.now();
    bot.chat(`//replace stone air`);
    await replaceDone;
    log(`//replace done in ${Date.now()-t0}ms`);
    await sleep(20000);  // generous drain time

    log('rolling back...');
    const rbT0 = Date.now();
    const rbDone = waitForChat(bot, /(reversals|No results|skipped)/i, 600000);
    bot.chat(`/spyglass rollback p:${BOT_NAME} t:10m -g`);
    const result = await rbDone;
    log(`rollback done in ${Date.now()-rbT0}ms: ${result}`);
    await sleep(5000);

    let stone=0, scanned=0;
    const stride = Math.floor((SIDE*SIDE*SIDE)/64);
    for (let i=0; i<SIDE*SIDE*SIDE && scanned<64; i+=stride) {
        const dx=i%SIDE, rest=Math.floor(i/SIDE), dy=rest%SIDE, dz=Math.floor(rest/SIDE);
        const r = await rcon(`execute if block ${x0+dx} ${y0+dy} ${z0+dz} minecraft:stone`);
        if (r.includes('passed')) stone++;
        scanned++;
    }
    log(`scan: ${stone}/${scanned} stone`);
    bot.quit();
    process.exit(stone === scanned ? 0 : 1);
})().catch(e=>{console.error(e);process.exit(2);});
