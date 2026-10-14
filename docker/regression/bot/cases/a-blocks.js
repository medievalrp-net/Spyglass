// Category A — block basics (use-cases.md A1–A10). All automated.
import {
    rcon, sleep, log, blockIs, blockAmong, nextPlot, claimPlot, releasePlot,
    placeAt, digAt, give, sgSearch, coLookup, sgOp, coOp, grep, drain,
    freshResult, check, startBot, NA,
} from './lib.js';

export default [

{
    id: 'A1', title: 'single block break: record + rollback on both sides',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            // Prime the dig path: a bot's first-ever dig only lands on a
            // block it placed itself (protocol state quirk); one scratch
            // place+dig makes the rcon-placed target diggable.
            await placeAt(bot, 'dirt', plot.cx - 2, plot.y, plot.cz);
            await digAt(bot, plot.cx - 2, plot.y, plot.cz);
            await rcon(`setblock ${x} ${y} ${z} stone`);
            await sleep(600);
            await digAt(bot, x, y, z);
            await drain();

            const sg = await sgSearch(bot, `p:${bot.username} t:65s r:8`);
            check(r, 'sg', grep(sg, /broke/i).some(l => /stone/i.test(l)),
                'search shows the break', `search missing break: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 a:-block`);
            check(r, 'cp', grep(cp, /stone/i).length > 0,
                'lookup shows the break', `lookup missing break: ${cp.slice(-3).join(' | ')}`);

            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            check(r, 'sg', rb.applied >= 1 && await blockIs(x, y, z, 'stone'),
                'rollback restored stone', `rollback: ${rb.line}; stone=${await blockIs(x, y, z, 'stone')}`);
            const un = await sgOp(bot, 'undo', '');
            check(r, 'sg', await blockIs(x, y, z, 'air'),
                'undo re-broke it', `undo left non-air: ${un.line}`);
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            check(r, 'cp', await blockIs(x, y, z, 'stone'),
                'CP rollback restored stone', 'CP rollback did not restore stone');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A2', title: 'single block place: record + rollback on both sides',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await placeAt(bot, 'dirt', x, y, z);
            check(r, 'sg', await blockIs(x, y, z, 'dirt'), 'placed dirt', 'bot failed to place dirt');
            await drain();

            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 a:place`);
            check(r, 'sg', grep(sg, /placed/i).some(l => /dirt/i.test(l)),
                'search shows the place', `search missing place: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 a:+block`);
            check(r, 'cp', grep(cp, /dirt/i).length > 0,
                'lookup shows the place', `lookup missing place: ${cp.slice(-3).join(' | ')}`);

            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            check(r, 'sg', rb.applied >= 1 && await blockIs(x, y, z, 'air'),
                'rollback removed it', `rollback: ${rb.line}`);
            await sgOp(bot, 'undo', '');
            check(r, 'sg', await blockIs(x, y, z, 'dirt'), 'undo re-placed it', 'undo did not re-place dirt');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            check(r, 'cp', await blockIs(x, y, z, 'air'),
                'CP rollback removed it', 'CP rollback left the dirt');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A3', title: 'mixed-material strip incl. stair states restores exact BlockData',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        // A row of 8: 4 oriented stairs + ore + glass + log + deepslate.
        const spec = [
            ['oak_stairs[facing=east,half=top]', 'oak_stairs'],
            ['oak_stairs[facing=west,half=bottom]', 'oak_stairs'],
            ['oak_stairs[facing=north,half=top]', 'oak_stairs'],
            ['oak_stairs[facing=south,half=bottom]', 'oak_stairs'],
            ['diamond_ore', 'diamond_ore'],
            ['glass', 'glass'],
            ['oak_log[axis=z]', 'oak_log'],
            ['deepslate', 'deepslate'],
        ];
        const y = plot.y, z = plot.cz;
        try {
            for (let i = 0; i < spec.length; i++) {
                await rcon(`setblock ${plot.x0 + 4 + i} ${y} ${z} ${spec[i][0]}`);
            }
            await sleep(600);
            // WE-break the whole row as the bot (logged), like the benches.
            bot.chat('//sel cuboid'); await sleep(250);
            bot.chat(`//pos1 ${plot.x0 + 4},${y},${z}`); await sleep(250);
            bot.chat(`//pos2 ${plot.x0 + 11},${y},${z}`); await sleep(250);
            bot.chat('//set air'); await sleep(1500);
            await drain();

            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            let exact = 0;
            for (let i = 0; i < spec.length; i++) {
                if (await blockIs(plot.x0 + 4 + i, y, z, spec[i][0])) exact++;
            }
            check(r, 'sg', rb.applied >= 8 && exact === spec.length,
                'all 8 blocks restored with exact states',
                `exact-state restores: ${exact}/8 (applied=${rb.applied})`);

            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:16`);
            let cpExact = 0;
            for (let i = 0; i < spec.length; i++) {
                if (await blockIs(plot.x0 + 4 + i, y, z, spec[i][0])) cpExact++;
            }
            check(r, 'cp', cpExact === spec.length,
                'CP restored all 8 with exact states', `CP exact-state restores: ${cpExact}/8`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A4', title: 'place-then-break same block nets to air (no ghost block)',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await placeAt(bot, 'dirt', x, y, z);
            await digAt(bot, x, y, z);
            await drain();
            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            check(r, 'sg', await blockIs(x, y, z, 'air'),
                `netted to air (${rb.line.trim()})`, 'ghost block after rollback');
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            check(r, 'cp', await blockIs(x, y, z, 'air'),
                'CP netted to air', 'CP left a ghost block');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A5', title: 'two actors, one block: per-actor rollback semantics',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        const botB = await startBot('ucb' + Date.now().toString(36).slice(-3));
        try {
            await rcon(`teleport ${botB.username} ${plot.cx + 0.5} ${plot.y} ${plot.cz + 2.5}`);
            await sleep(1500);
            // Prime B's dig path on its own scratch block first.
            await placeAt(botB, 'dirt', plot.cx - 2, plot.y, plot.cz + 2);
            await digAt(botB, plot.cx - 2, plot.y, plot.cz + 2);
            await placeAt(bot, 'dirt', x, y, z);          // A places dirt
            await digAt(botB, x, y, z);                    // B breaks it
            await placeAt(botB, 'cobblestone', x, y, z);   // B places cobble
            await drain();

            // Roll back only A. A's place is buried under B's edits.
            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            const sgState = await blockAmong(x, y, z, ['cobblestone', 'air', 'dirt']);
            check(r, 'sg', sgState === 'cobblestone' && /skipped/i.test(rb.line),
                `B's cobble preserved, A's buried place skipped (${rb.line.trim()})`,
                `state=${sgState}, line=${rb.line.trim()} — expected cobble + skip`);

            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            const cpState = await blockAmong(x, y, z, ['cobblestone', 'air', 'dirt']);
            r.notes.push(`CP per-actor semantics: block=${cpState} after rolling back only A (SG: ${sgState})`);
            // Internally-consistent outcomes both score pass; the note records the semantic.
        } finally {
            botB.quit(); await sleep(800);
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'A6', title: 'radius boundary r:5: outside untouched on both sides',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const y = plot.y, z = plot.cz;
        const xIn = plot.cx + 5, xOut = plot.cx + 7;
        try {
            await placeAt(bot, 'dirt', xIn, y, z).catch(() => rcon(`setblock ${xIn} ${y} ${z} dirt`));
            // The far block may be out of the bot's reach — place via a
            // quick TP hop so both are bot-attributed.
            await rcon(`teleport ${bot.username} ${xOut + 0.5} ${y} ${z - 1.5}`); await sleep(900);
            await placeAt(bot, 'dirt', xOut, y, z);
            await rcon(`teleport ${bot.username} ${plot.cx + 0.5} ${y} ${plot.cz + 0.5}`); await sleep(900);
            await drain();

            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:5`);
            const sgIn = await blockIs(xIn, y, z, 'air'), sgOut = await blockIs(xOut, y, z, 'dirt');
            check(r, 'sg', sgOut, 'block at distance 7 untouched', 'r:5 touched a block at distance 7');
            r.notes.push(`boundary at exactly 5: SG ${sgIn ? 'included' : 'excluded'}`);

            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:5`);
            const cpIn = await blockIs(xIn, y, z, 'air'), cpOut = await blockIs(xOut, y, z, 'dirt');
            check(r, 'cp', cpOut, 'CP left distance-7 untouched', 'CP r:5 touched distance 7');
            r.notes.push(`boundary at exactly 5: CP ${cpIn ? 'included' : 'excluded'}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A7', title: 'time window: t:8s only reverts the younger grief',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const y = plot.y, z = plot.cz;
        const x1 = plot.cx + 1, x2 = plot.cx + 3;
        try {
            await placeAt(bot, 'dirt', x1, y, z);
            await sleep(12_000);
            await placeAt(bot, 'dirt', x2, y, z);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:8s r:8`);
            check(r, 'sg', await blockIs(x1, y, z, 'dirt') && await blockIs(x2, y, z, 'air'),
                'only the young place reverted',
                `old=${await blockAmong(x1, y, z, ['dirt', 'air'])} young=${await blockAmong(x2, y, z, ['dirt', 'air'])}`);
            await coOp(bot, 'rollback', `u:${bot.username} t:8s r:8`);
            check(r, 'cp', await blockIs(x1, y, z, 'dirt') && await blockIs(x2, y, z, 'air'),
                'CP honored the window', 'CP window wrong: old grief touched or young left');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A8', title: 'action filter: roll back only breaks, placements stay',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const y = plot.y, z = plot.cz;
        const xPlace = plot.cx + 1, xBreak = plot.cx + 3;
        try {
            await placeAt(bot, 'dirt', xPlace, y, z);
            await rcon(`setblock ${xBreak} ${y} ${z} stone`); await sleep(500);
            await digAt(bot, xBreak, y, z);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8 a:break`);
            check(r, 'sg',
                await blockIs(xPlace, y, z, 'dirt') && await blockIs(xBreak, y, z, 'stone'),
                'break restored, place untouched',
                `place=${await blockAmong(xPlace, y, z, ['dirt', 'air'])} break=${await blockAmong(xBreak, y, z, ['stone', 'air'])}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8 a:-block`);
            check(r, 'cp',
                await blockIs(xPlace, y, z, 'dirt') && await blockIs(xBreak, y, z, 'stone'),
                'CP a:-block matched', 'CP a:-block touched the place or missed the break');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A9', title: 'block filter: roll back only diamond ore',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const y = plot.y, z = plot.cz;
        const xOre = plot.cx + 1, xStone = plot.cx + 3;
        try {
            await rcon(`setblock ${xOre} ${y} ${z} diamond_ore`);
            await rcon(`setblock ${xStone} ${y} ${z} stone`);
            await sleep(500);
            await digAt(bot, xOre, y, z);
            await digAt(bot, xStone, y, z);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8 b:diamond_ore`);
            check(r, 'sg',
                await blockIs(xOre, y, z, 'diamond_ore') && await blockIs(xStone, y, z, 'air'),
                'only the ore came back',
                `ore=${await blockAmong(xOre, y, z, ['diamond_ore', 'air'])} stone=${await blockAmong(xStone, y, z, ['stone', 'air'])}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8 b:diamond_ore`);
            check(r, 'cp',
                await blockIs(xOre, y, z, 'diamond_ore') && await blockIs(xStone, y, z, 'air'),
                'CP b: filter matched', 'CP b:diamond_ore wrong scope');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'A10', title: 'rollback into unloaded chunks loads and restores',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} stone`); await sleep(500);
            await digAt(bot, x, y, z);
            await drain();
            // Walk away and unload the plot.
            await rcon(`teleport ${bot.username} ${plot.cx + 600} ${plot.y + 20} ${plot.cz}`);
            await sleep(2000);
            await rcon(`forceload remove ${plot.x0} ${plot.z0} ${plot.x1} ${plot.z1}`);
            await sleep(1500);

            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s -g`);
            await rcon(`forceload add ${plot.x0} ${plot.z0} ${plot.x1} ${plot.z1}`);
            await sleep(800);
            check(r, 'sg', rb.applied >= 1 && await blockIs(x, y, z, 'stone'),
                'restored across an unloaded chunk', `applied=${rb.applied}, stone=${await blockIs(x, y, z, 'stone')}`);

            await sgOp(bot, 'undo', '');
            await rcon(`forceload remove ${plot.x0} ${plot.z0} ${plot.x1} ${plot.z1}`);
            await sleep(1200);
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:#global`);
            await rcon(`forceload add ${plot.x0} ${plot.z0} ${plot.x1} ${plot.z1}`);
            await sleep(800);
            check(r, 'cp', await blockIs(x, y, z, 'stone'),
                'CP restored across an unloaded chunk', 'CP missed the unloaded chunk');
        } finally { await releasePlot(plot); }
        return r;
    },
},

];
