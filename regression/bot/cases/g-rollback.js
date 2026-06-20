// Category G — rollback semantics (use-cases.md G1–G14).
import {
    rcon, sleep, blockIs, blockAmong, blockData, nextPlot, claimPlot,
    releasePlot, placeAt, digAt, sgSearch, coLookup, sgOp, coOp, grep, drain,
    freshResult, check, startBot, NA,
} from './lib.js';

// Shared mini-grief: an 8×1×8 stone slab WE-broken by the bot.
async function slabGrief(bot, plot) {
    await rcon(`fill ${plot.x0 + 4} ${plot.y} ${plot.z0 + 4} ${plot.x0 + 11} ${plot.y} ${plot.z0 + 11} stone`);
    await sleep(600);
    bot.chat('//sel cuboid'); await sleep(250);
    bot.chat(`//pos1 ${plot.x0 + 4},${plot.y},${plot.z0 + 4}`); await sleep(250);
    bot.chat(`//pos2 ${plot.x0 + 11},${plot.y},${plot.z0 + 11}`); await sleep(250);
    bot.chat('//set air'); await sleep(1500);
    await drain();
}
const SLAB_SAMPLES = plot => [
    [plot.x0 + 4, plot.y, plot.z0 + 4], [plot.x0 + 11, plot.y, plot.z0 + 11],
    [plot.x0 + 7, plot.y, plot.z0 + 8], [plot.x0 + 11, plot.y, plot.z0 + 4],
];
async function allAre(samples, type) {
    for (const [x, y, z] of samples) if (!(await blockIs(x, y, z, type))) return false;
    return true;
}

export default [

{
    id: 'G1', title: 'grief→rollback: server-truth sampling, both sides',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const samples = SLAB_SAMPLES(plot);
        try {
            await slabGrief(bot, plot);
            check(r, 'sg', await allAre(samples, 'air'), 'grief landed', 'grief setup failed');
            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            // World truth is the assertion; the applied count tolerates
            // ClickHouse async-insert duplicates (a double-flushed row's
            // second copy skips on the expected-state check and the
            // world converges — applied+skipped still covers the slab).
            const skippedMatch = rb.line.match(/(\d+)\s+skipped/);
            const covered = rb.applied + (skippedMatch ? parseInt(skippedMatch[1], 10) : 0);
            check(r, 'sg', covered >= 64 && await allAre(samples, 'stone'),
                `rollback restored all samples (${rb.line.trim()})`,
                `world wrong or short coverage (${covered}/64): ${rb.line.trim()}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:16`);
            check(r, 'cp', await allAre(samples, 'stone'),
                'CP rollback restored all samples', 'CP samples not all stone');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'G2', title: 'undo is the exact inverse of the rollback',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const samples = SLAB_SAMPLES(plot);
        try {
            await slabGrief(bot, plot);
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            const un = await sgOp(bot, 'undo', '');
            check(r, 'sg', await allAre(samples, 'air'),
                `undo returned the griefed state (${un.line.trim()})`, 'undo did not restore the post-grief state');
            // CP has no undo; restore is the manual inverse — exercised in G12.
            r.cp.verdict = NA;
            r.notes.push('CP has no undo stack; /co restore is a manual inverse (see G12)');
        } finally {
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:16`);
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'G3', title: 'identical rollback re-run: skips, no double-apply',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const samples = SLAB_SAMPLES(plot);
        try {
            await slabGrief(bot, plot);
            const rb1 = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            const rb2 = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            check(r, 'sg', rb1.applied >= 64 && rb2.applied === 0 && await allAre(samples, 'stone'),
                `re-run applied 0 (first ${rb1.applied}; re-run: ${rb2.line.trim()})`,
                `re-run applied ${rb2.applied}, expected 0 — fast-path force-overwrite, issue #27`);
            const cp1 = await coOp(bot, 'rollback', `u:${bot.username} t:60s r:16`);
            r.notes.push(`CP rollback after SG already restored: ${cp1.line.trim()} (world already correct)`);
            check(r, 'cp', await allAre(samples, 'stone'), 'world stays correct', 'CP disturbed a restored world');
        } finally {
            await sgOp(bot, 'undo', '').catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'G4', title: 'undo stack is LIFO per operator',
    async run() {
        const r = freshResult();
        // Fresh bot = pristine undo stack.
        const bot = await startBot('ucg4' + Date.now().toString(36).slice(-2));
        const plotA = nextPlot(), plotB = nextPlot();
        try {
            await claimPlot(bot, plotA);
            const a = [plotA.cx + 1, plotA.y, plotA.cz];
            await placeAt(bot, 'dirt', ...a);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);     // op1: removes A-dirt

            await claimPlot(bot, plotB);
            const b = [plotB.cx + 1, plotB.y, plotB.cz];
            await placeAt(bot, 'dirt', ...b);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);     // op2: removes B-dirt

            await sgOp(bot, 'undo', '');                                     // pops op2
            const afterFirst = (await blockIs(...b, 'dirt')) && (await blockIs(...a, 'air'));
            check(r, 'sg', afterFirst, 'first undo reversed the newest op only',
                `after undo1: B=${await blockAmong(...b, ['dirt', 'air'])} A=${await blockAmong(...a, ['dirt', 'air'])}`);
            await sgOp(bot, 'undo', '');                                     // pops op1
            check(r, 'sg', await blockIs(...a, 'dirt'), 'second undo reversed the older op',
                'second undo did not reach the older op');
            r.cp.verdict = NA;
            r.notes.push('CP has no undo stack to compare');
        } finally {
            bot.quit(); await sleep(800);
            await releasePlot(plotA); await releasePlot(plotB);
        }
        return r;
    },
},

{ id: 'G5', title: 'player inside region during rollback', manual: 'needs a human to judge suffocation/clip UX' },
{ id: 'G6', title: 'preview before applying', manual: 'CP #preview exists; SG has no preview — known CP-ahead capability cell' },
{ id: 'G7', title: 'cancel mid-rollback', manual: 'needs a multi-second op; exercised ad-hoc during the perf campaign' },
{ id: 'G8', title: 'crash mid-rollback, resume on restart', manual: 'kill -9 orchestration unsafe in the shared suite run; resume path unit/IT-covered' },

{
    id: 'G9', title: 'grief spanning loaded + unloaded chunks restores both',
    async run(bot) {
        const r = freshResult();
        const plotL = nextPlot(), plotU = nextPlot();
        try {
            await claimPlot(bot, plotL);
            const l = [plotL.cx + 1, plotL.y, plotL.cz];
            await rcon(`setblock ${l[0]} ${l[1]} ${l[2]} stone`); await sleep(400);
            await digAt(bot, ...l);
            await claimPlot(bot, plotU);
            const u = [plotU.cx + 1, plotU.y, plotU.cz];
            await rcon(`setblock ${u[0]} ${u[1]} ${u[2]} stone`); await sleep(400);
            await digAt(bot, ...u);
            await drain();
            // Unload U, stand in L.
            await rcon(`teleport ${bot.username} ${plotL.cx + 0.5} ${plotL.y} ${plotL.cz + 0.5}`);
            await sleep(1200);
            await rcon(`forceload remove ${plotU.x0} ${plotU.z0} ${plotU.x1} ${plotU.z1}`);
            await sleep(1000);
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s -g`);
            await rcon(`forceload add ${plotU.x0} ${plotU.z0} ${plotU.x1} ${plotU.z1}`);
            await sleep(800);
            check(r, 'sg', (await blockIs(...l, 'stone')) && (await blockIs(...u, 'stone')),
                'both halves restored', `loaded=${await blockIs(...l, 'stone')} unloaded=${await blockIs(...u, 'stone')}`);
            await sgOp(bot, 'undo', '');
            await rcon(`forceload remove ${plotU.x0} ${plotU.z0} ${plotU.x1} ${plotU.z1}`);
            await sleep(800);
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:#global`);
            await rcon(`forceload add ${plotU.x0} ${plotU.z0} ${plotU.x1} ${plotU.z1}`);
            await sleep(800);
            check(r, 'cp', (await blockIs(...l, 'stone')) && (await blockIs(...u, 'stone')),
                'CP restored both halves', 'CP missed a half');
        } finally { await releasePlot(plotL); await releasePlot(plotU); }
        return r;
    },
},

{
    id: 'G10', title: 'rolled-* audit entries visible but not re-rollbackable',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const samples = SLAB_SAMPLES(plot);
        try {
            await slabGrief(bot, plot);
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            await drain();
            const sg = await sgSearch(bot, `a:rolled-place t:60s r:16`);
            check(r, 'sg', grep(sg, /ROLLBACK|rolled/i).length > 0 || grep(sg, /placed/i).length > 0,
                'synthesized rolled entries searchable', `no rolled entries: ${sg.slice(-3).join(' | ')}`);
            // Attempting to roll back the audit must not undo the rollback.
            await sgOp(bot, 'rollback', `a:rolled-place t:60s r:16`);
            check(r, 'sg', await allAre(samples, 'stone'),
                'audit entries are not re-rollbackable', 'rolling back rolled-place changed the world');
            // CP marks rolled rows in lookup output rather than synthesizing.
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:16 a:block`);
            r.notes.push(`CP lookup after CP-unrelated SG rollback shows raw events (${grep(cp, /stone/i).length} lines)`);
        } finally {
            await sgOp(bot, 'undo', '').catch(() => {});
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:16`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'G11', title: 'chest with loot inside the crater restores contents',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} chest`);
            await rcon(`data merge block ${x} ${y} ${z} {Items:[{Slot:0b,id:"minecraft:diamond",count:13}]}`);
            await sleep(600);
            await digAt(bot, x, y, z);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            const sgItems = await blockData(x, y, z, 'Items');
            check(r, 'sg', /diamond/.test(sgItems) && /13/.test(sgItems),
                'chest restored WITH its 13 diamonds', `restored chest items: ${sgItems.slice(0, 120) || '(none)'}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            const cpItems = await blockData(x, y, z, 'Items');
            check(r, 'cp', /diamond/.test(cpItems) && /13/.test(cpItems),
                'CP restored the contents too', `CP restored items: ${cpItems.slice(0, 120) || '(none)'}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'G12', title: 'restore inverts an over-broad rollback',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const samples = SLAB_SAMPLES(plot);
        try {
            await slabGrief(bot, plot);
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            await sgOp(bot, 'restore', `p:${bot.username} t:60s r:16`);
            check(r, 'sg', await allAre(samples, 'air'),
                'restore returned the post-grief state', 'restore did not invert the rollback');
            await coOp(bot, 'rollback', `u:${bot.username} t:90s r:16`);
            await coOp(bot, 'restore', `u:${bot.username} t:90s r:16`);
            check(r, 'cp', await allAre(samples, 'air'),
                'CP restore inverted its rollback', 'CP restore did not invert');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'G13', title: 'exclusion filter: everything except chests',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const y = plot.y, z = plot.cz;
        const xChest = plot.cx + 1, xStone = plot.cx + 3;
        try {
            await rcon(`setblock ${xChest} ${y} ${z} chest`);
            await rcon(`setblock ${xStone} ${y} ${z} stone`);
            await sleep(500);
            await digAt(bot, xChest, y, z);
            await digAt(bot, xStone, y, z);
            await drain();
            const rbEx = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8 b:!chest`);
            r.notes.push(`G13 SG b:!chest line: ${rbEx.line.trim()}`);
            check(r, 'sg',
                (await blockIs(xStone, y, z, 'stone')) && (await blockIs(xChest, y, z, 'air')),
                'stone restored, chest excluded',
                `stone=${await blockAmong(xStone, y, z, ['stone', 'air'])} chest=${await blockAmong(xChest, y, z, ['chest', 'air'])}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8 e:chest`);
            check(r, 'cp',
                (await blockIs(xStone, y, z, 'stone')) && (await blockIs(xChest, y, z, 'air')),
                'CP e:chest matched', 'CP exclusion wrong scope');
        } finally {
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{ id: 'G14', title: 'rollback during live ingest (read-your-writes)', manual: 'flush-gate behavior; covered by rollback-flush-timeout design + perf campaign, noisy in a shared suite' },

];
