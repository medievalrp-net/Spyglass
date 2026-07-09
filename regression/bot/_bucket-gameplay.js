// End-to-end proof for bucket fluid logging (#228). As a real player the bot:
//   - empties a water / lava / powder-snow bucket  -> a `bucket-empty` record
//   - fills a bucket from a water source            -> a `bucket-fill` record
// then for each: /spyglass search finds it, and /spyglass rollback REMOVES the
// poured fluid (empty) or RESTORES the scooped source (fill). World state is
// asserted via RCON `execute if block`, never a plugin summary alone.
//
// Buckets fire PlayerBucketEmpty/FillEvent only from a real interaction, so the
// bot uses the item (activateItem); vanilla BucketItem raycasts server-side from
// the player's look, so lookAt(target) + activateItem() places/collects the
// fluid at the looked-at block. Exits 0 all-pass, 1 failed check, 2 harness error.
//
// Env: SG_PORT / SG_RCON_PORT / SG_RCON_PASS (see the runner). BOT_VERSION optional.
import mineflayer from 'mineflayer';
import { Vec3 } from 'vec3';
import {
    HOST, PORT, rcon, sleep, blockIs,
    nextPlot, claimPlot, releasePlot, give, equip,
    sgSearch, sgOp, drain, grep,
} from './cases/lib.js';

const NAME = process.env.BOT_NAME || ('bk' + Date.now().toString(36).slice(-4));
const VERSION = process.env.BOT_VERSION || false;

const checks = [];
const ck = (ok, note) => { checks.push({ ok: !!ok, note }); console.log(`${ok ? 'ok  ' : 'FAIL'}  ${note}`); };

// Pour a fluid bucket (water/lava) so the fluid lands at (x,y,z): aim at the top
// face of the solid block below the target and use the item. A fluid BucketItem
// raycasts server-side on a use-item, so activateItem() places it. Re-equips (a
// poured bucket empties) and verifies server-side across a couple of aim points.
async function emptyBucket(bot, bucketItem, fluid, x, y, z) {
    const aims = [
        new Vec3(x + 0.5, y, z + 0.5),       // top face of block below target
        new Vec3(x + 0.5, y + 0.5, z + 0.5), // target centre
    ];
    for (let attempt = 0; attempt < aims.length; attempt++) {
        await equip(bot, bucketItem);
        await bot.lookAt(aims[attempt], true);
        await sleep(400);
        try { bot.activateItem(); } catch { /* verified below */ }
        await sleep(1000);
        try { bot.deactivateItem(); } catch { }
        if (await blockIs(x, y, z, fluid)) return true;
        await sleep(600);
    }
    return blockIs(x, y, z, fluid);
}

// Fill a bucket from the fluid source at (x,y,z): aim at the source centre and
// use an empty bucket. Verifies the source is gone server-side, one retry.
async function fillBucket(bot, x, y, z) {
    const aims = [
        new Vec3(x + 0.5, y + 0.5, z + 0.5), // source centre
        new Vec3(x + 0.5, y + 0.8, z + 0.5), // upper part (surface)
        new Vec3(x + 0.5, y + 0.2, z + 0.5),
    ];
    for (let attempt = 0; attempt < aims.length; attempt++) {
        await equip(bot, 'bucket');
        await bot.lookAt(aims[attempt], true);
        await sleep(400);
        try { bot.activateItem(); } catch { /* verified below */ }
        await sleep(1000);
        try { bot.deactivateItem(); } catch { }
        if (await blockIs(x, y, z, 'air')) return true;
        await sleep(600);
    }
    return blockIs(x, y, z, 'air');
}

// One empty sub-test on its own plot: pour, search, rollback removes it.
async function emptyCase(bot, label, bucketItem, fluid) {
    const plot = nextPlot();
    await claimPlot(bot, plot);
    const T = [plot.cx + 2, plot.y, plot.cz];
    try {
        const poured = await emptyBucket(bot, bucketItem, fluid, ...T);
        ck(poured, `${label}: emptied bucket -> ${fluid} source at ${T.join(',')}`);
        await drain();

        const lines = await sgSearch(bot, `p:${NAME} a:bucket-empty r:8 t:300s`);
        const hit = grep(lines, new RegExp(`poured.*${fluid}|${fluid}`, 'i'));
        ck(hit.length > 0, `${label}: /spyglass search a:bucket-empty shows ${fluid} (${(hit[0] || '(none)').trim()})`);

        const rb = await sgOp(bot, 'rollback', `p:${NAME} a:bucket-empty r:8 t:300s`);
        ck(rb.applied >= 1, `${label}: rollback applied ${rb.applied} (${rb.line.trim()})`);
        await sleep(1500); // let any flow recede once the source is gone
        ck(await blockIs(...T, 'air'), `${label}: rollback REMOVED the poured ${fluid} (${T.join(',')} -> air)`);
    } finally {
        await releasePlot(plot).catch(() => { });
    }
}

// The fill sub-test: seed a water source (unlogged), scoop it, rollback restores.
async function fillCase(bot) {
    const plot = nextPlot();
    await claimPlot(bot, plot);
    const S = [plot.cx + 2, plot.y, plot.cz];
    try {
        // Unlogged setup: an open water source (no walls - a stone pocket would
        // block the bot's line of sight to it). Flowing spread is harmless: an
        // empty bucket raycasts SOURCE_ONLY, so it still collects the source.
        await rcon(`setblock ${S[0]} ${S[1]} ${S[2]} water`);
        await sleep(600);
        ck(await blockIs(...S, 'water'), `fill: seeded a water source at ${S.join(',')}`);

        const scooped = await fillBucket(bot, ...S);
        ck(scooped, `fill: scooped the source (${S.join(',')} -> air)`);
        await drain();

        const lines = await sgSearch(bot, `p:${NAME} a:bucket-fill r:8 t:300s`);
        const hit = grep(lines, /scooped.*WATER|WATER/i);
        ck(hit.length > 0, `fill: /spyglass search a:bucket-fill shows WATER (${(hit[0] || '(none)').trim()})`);

        // Plain coordinate lookup (no a:) must surface the bucket event too.
        const plain = await sgSearch(bot, `p:${NAME} r:8 t:300s`);
        ck(grep(plain, /scooped|bucket|WATER/i).length > 0, 'fill: plain lookup at the coords surfaces the event');

        const rb = await sgOp(bot, 'rollback', `p:${NAME} a:bucket-fill r:8 t:300s`);
        ck(rb.applied >= 1, `fill: rollback applied ${rb.applied} (${rb.line.trim()})`);
        await sleep(1200);
        ck(await blockIs(...S, 'water'), `fill: rollback RESTORED the scooped water (${S.join(',')} -> water)`);
    } finally {
        await releasePlot(plot).catch(() => { });
    }
}

(async () => {
    const bot = mineflayer.createBot({
        host: HOST, port: PORT, username: NAME,
        version: VERSION, auth: 'offline', checkTimeoutInterval: 180_000,
    });
    await new Promise((res, rej) => {
        bot.once('spawn', res);
        bot.once('error', rej);
        bot.once('kicked', r => rej(new Error('kicked: ' + (typeof r === 'string' ? r : JSON.stringify(r)))));
        setTimeout(() => rej(new Error('spawn timeout (60s)')), 60_000);
    });
    await rcon(`op ${NAME}`);
    await rcon(`gamemode creative ${NAME}`);
    await sleep(800);
    bot.chatLog = [];
    bot.on('messagestr', m => bot.chatLog.push(m));

    // BUCKET_ONLY=water,fill restricts the run (default: all). Powder snow is a
    // BlockItem (fires BlockPlaceEvent, logged as `place`) so it is out of scope
    // for this listener and not exercised here.
    const only = (process.env.BUCKET_ONLY || '').split(',').map(s => s.trim()).filter(Boolean);
    const want = name => only.length === 0 || only.includes(name);
    try {
        if (want('water')) await emptyCase(bot, 'water', 'water_bucket', 'water');
        if (want('lava')) await emptyCase(bot, 'lava', 'lava_bucket', 'lava');
        if (want('fill')) await fillCase(bot);
    } catch (e) {
        ck(false, 'harness error: ' + (e?.message || e));
    }

    bot.quit();
    await sleep(1000);
    const failed = checks.filter(c => !c.ok).length;
    console.log(`\nBUCKET ${failed === 0 ? 'PASS' : 'FAIL'} - ${checks.length - failed}/${checks.length} checks (v=${VERSION})`);
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => { console.log('BUCKET ERROR:', e?.message || e); process.exit(2); });
