// Focused, SG-only gameplay + rollback proof for the cross-version matrix.
//
// Places a block and breaks a block as a real player (logged events), then:
//   /spyglass search   - the two actions are recorded
//   /spyglass rollback  - placed block reverts to air, broken block restored
//   /spyglass undo      - the rollback is inverted (place back, break back)
// World state is asserted via RCON `execute if block`, never a plugin summary
// alone. No WorldEdit, no CoreProtect - just the core record→query→rollback→undo
// loop. Exits 0 on all-pass, 1 on a failed check, 2 on a harness error.
//
// Env: BOT_VERSION (e.g. 1.21.8), BOT_NAME. Server must be on 127.0.0.1:25566
// with RCON on 25576 (pass test123) - see scripts/gameplay-test.sh.
import mineflayer from 'mineflayer';
import {
    HOST, PORT,
    rcon, sleep, blockIs, nextPlot, claimPlot, releasePlot,
    placeAt, digAt, sgSearch, sgOp, drain, grep,
} from './cases/lib.js';

const NAME = process.env.BOT_NAME || ('gp' + Date.now().toString(36).slice(-4));
const VERSION = process.env.BOT_VERSION || false;

const checks = [];
const ck = (ok, note) => { checks.push({ ok: !!ok, note }); console.log(`${ok ? 'ok  ' : 'FAIL'}  ${note}`); };

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

    const plot = nextPlot();
    await claimPlot(bot, plot);
    const y = plot.y;
    const A = [plot.cx + 1, y, plot.cz];   // bot PLACES stone here  (a place event)
    const B = [plot.cx + 3, y, plot.cz];   // bot BREAKS stone here  (a break event)

    try {
        // ── actions (logged as the player) ──────────────────────────
        await placeAt(bot, 'stone', ...A);
        ck(await blockIs(...A, 'stone'), `placed stone at A ${A.join(',')}`);

        await rcon(`setblock ${B[0]} ${B[1]} ${B[2]} stone`); await sleep(500); // unlogged setup
        await digAt(bot, ...B);
        ck(await blockIs(...B, 'air'), `broke stone at B ${B.join(',')}`);
        await drain();

        // ── command: search finds both actions ──────────────────────
        const lines = await sgSearch(bot, `p:${NAME} t:120s r:16`);
        const hdr = lines.find(l => /(\d+)\s+results/.test(l));
        const count = hdr ? parseInt(hdr.match(/(\d+)\s+results/)[1], 10)
            : grep(lines, /plac|broke|remov|stone/i).length;
        ck(count >= 2, `/spyglass search found the actions (count=${count})`);

        // ── rollback reverts the world ──────────────────────────────
        const rb = await sgOp(bot, 'rollback', `p:${NAME} t:120s r:16`);
        ck(rb.applied >= 2, `/spyglass rollback applied ${rb.applied} (${rb.line.trim()})`);
        ck(await blockIs(...A, 'air'), 'rollback reverted the placed block (A → air)');
        ck(await blockIs(...B, 'stone'), 'rollback restored the broken block (B → stone)');

        // ── undo is the inverse ─────────────────────────────────────
        const un = await sgOp(bot, 'undo', '');
        ck(await blockIs(...A, 'stone'), `undo re-applied the place (A → stone) (${un.line.trim()})`);
        ck(await blockIs(...B, 'air'), 'undo re-applied the break (B → air)');
    } finally {
        await releasePlot(plot).catch(() => {});
    }

    bot.quit();
    await sleep(1000);
    const failed = checks.filter(c => !c.ok).length;
    console.log(`\nGAMEPLAY ${failed === 0 ? 'PASS' : 'FAIL'} - ${checks.length - failed}/${checks.length} checks (v=${VERSION})`);
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => { console.log('GAMEPLAY ERROR:', e?.message || e); process.exit(2); });
