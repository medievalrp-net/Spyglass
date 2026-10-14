// Category F — natural & environment (use-cases.md F1–F10).
import {
    rcon, sleep, blockIs, nextPlot, claimPlot, releasePlot, placeAt, equip,
    sgOp, coOp, drain, freshResult, check,
} from './lib.js';
import { Vec3 } from 'vec3';

async function countStoneFloor(plot, points) {
    let n = 0;
    for (const [dx, dz] of points) {
        if (await blockIs(plot.cx + dx, plot.y - 1, plot.cz + dz, 'stone')) n++;
    }
    return n;
}
const FLOOR_POINTS = [[0, 0], [1, 0], [0, 1], [-1, 0], [0, -1], [1, 1]];

export default [

{ id: 'F1', title: 'flint-and-steel fire spread attribution', manual: 'fire spread is slow/random; staging checklist' },

{
    id: 'F2', title: 'player TNT: crater attributed and rolled back',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 2, plot.y, plot.cz];
        try {
            await placeAt(bot, 'tnt', x, y, z);
            await equip(bot, 'flint_and_steel');
            const tnt = bot.blockAt(new Vec3(x, y, z));
            await bot.activateBlock(tnt);
            await sleep(6000);   // fuse 4s + settle
            const crater = (await countStoneFloor(plot, FLOOR_POINTS)) < FLOOR_POINTS.length;
            check(r, 'sg', crater, 'TNT made a crater', 'no crater — ignition failed');
            await drain();

            const rb = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:16`);
            const sgFixed = await countStoneFloor(plot, FLOOR_POINTS);
            check(r, 'sg', sgFixed === FLOOR_POINTS.length,
                `crater restored, attributed to placer (${rb.line.trim()})`,
                `floor ${sgFixed}/${FLOOR_POINTS.length} after p:-rollback — TNT attribution gap?`);

            await sgOp(bot, 'undo', '');
            const cp1 = await coOp(bot, 'rollback', `u:${bot.username} t:60s r:16`);
            let cpFixed = await countStoneFloor(plot, FLOOR_POINTS);
            if (cpFixed < FLOOR_POINTS.length) {
                const cp2 = await coOp(bot, 'rollback', `u:#tnt t:60s r:16`);
                cpFixed = await countStoneFloor(plot, FLOOR_POINTS);
                r.notes.push(`CP needed u:#tnt (u:player → "${cp1.line.trim()}"; u:#tnt → "${cp2.line.trim()}")`);
            }
            check(r, 'cp', cpFixed === FLOOR_POINTS.length,
                'CP restored the crater', `CP floor ${cpFixed}/${FLOOR_POINTS.length}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{ id: 'F3', title: 'creeper explosion: environment-source rollback', manual: 'ignited creepers explode on this server only without players nearby (probe verified; some plugin intervenes) — staging checklist' },

{ id: 'F4', title: 'enderman block grief', manual: 'enderman pickup is rare/random; staging checklist' },

{ id: 'F5', title: 'water flow into a build: recorded and dried out', manual: 'bucket emptying is not reliably bot-scriptable in 1.21; staging checklist (place water, sg rollback should dry the source)' },

{ id: 'F6', title: 'ice/snow churn storage cost', manual: 'needs biome rig + long horizon; storage method in perf docs' },
{ id: 'F7', title: 'leaf decay rollback', manual: 'decay timing is minutes-scale random; staging checklist' },
{ id: 'F8', title: 'sculk spread from mob death', manual: 'sculk catalyst rig; staging checklist' },
{ id: 'F9', title: 'wither containment grief', manual: 'boss fight not suite-safe' },
{ id: 'F10', title: 'lightning fire attribution', manual: 'lightning rig; staging checklist' },

];
