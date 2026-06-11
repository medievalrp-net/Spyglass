// Category D — entities (use-cases.md D1–D10).
import {
    rcon, sleep, entityCountNear, nextPlot, claimPlot, releasePlot, equip,
    sgSearch, coLookup, sgOp, coOp, grep, drain, freshResult, check,
} from './lib.js';

export default [

{
    id: 'D1', title: 'player kills a mob: record + entity rollback both sides',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 2, plot.y, plot.cz + 2];
        try {
            await rcon(`summon minecraft:cow ${x} ${y} ${z} {NoAI:1b}`);
            await sleep(1000);
            await equip(bot, 'netherite_sword');
            // Swing until the cow is gone (creative + netherite ≈ 2 hits).
            const t0 = Date.now();
            while (await entityCountNear(x, y, z, 'cow', 6) > 0 && Date.now() - t0 < 15000) {
                const cow = bot.nearestEntity(e => e.name === 'cow');
                if (cow) { try { await bot.attack(cow); } catch { } }
                await sleep(700);
            }
            check(r, 'sg', await entityCountNear(x, y, z, 'cow', 6) === 0,
                'cow killed', 'bot failed to kill the cow');
            await drain();

            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 a:death`);
            check(r, 'sg', grep(sg, /killed/i).some(l => /cow/i.test(l)),
                'kill recorded', `no death hit: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 a:kill`);
            check(r, 'cp', grep(cp, /cow/i).length > 0,
                'kill recorded', `no a:kill hit: ${cp.slice(-3).join(' | ')}`);

            // Entity rollback: the cow comes back.
            const rbD = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8 a:death`);
            r.notes.push(`D1 SG rollback line: ${rbD.line.trim()}`);
            await sleep(1500);
            const sgRevived = await entityCountNear(x, y, z, 'cow', 8);
            check(r, 'sg', sgRevived > 0, 'rollback resurrected the cow',
                'no cow after entity rollback');
            await sgOp(bot, 'undo', '');
            await sleep(1500);
            const afterUndo = await entityCountNear(x, y, z, 'cow', 8);
            r.notes.push(`SG undo of resurrection: cow count ${sgRevived}→${afterUndo}`);
            await rcon(`kill @e[type=cow,x=${x},y=${y},z=${z},distance=..10]`); await sleep(500);
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8 a:kill`);
            await sleep(1500);
            check(r, 'cp', await entityCountNear(x, y, z, 'cow', 8) > 0,
                'CP resurrected the cow', 'no cow after CP a:kill rollback');
            await rcon(`kill @e[type=cow,x=${x},y=${y},z=${z},distance=..10]`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{ id: 'D2', title: 'named pet kill + resurrection fidelity', manual: 'tame/name choreography; fidelity diff (name, owner, collar) needs NBT-deep compare' },
{ id: 'D3', title: 'nametag rename', manual: 'entity right-click choreography' },
{ id: 'D4', title: 'mount/dismount trail', manual: 'mount choreography; CP expected N/A' },
{ id: 'D5', title: 'PvP shot/hit forensics', manual: 'two-bot ranged combat choreography' },
{ id: 'D6', title: 'painting/item frame broken', manual: 'hanging entity placement choreography' },
{ id: 'D7', title: 'leashed mob dies to lava: no false attribution', manual: 'leash + lava rig' },
{ id: 'D8', title: 'spawn egg attribution', manual: 'egg use is bot-able but warden cleanup is not suite-safe; staging checklist' },
{ id: 'D9', title: 'villager killed by zombie', manual: 'mob-vs-mob rig' },
{ id: 'D10', title: 'wither block grief rollback', manual: 'boss fight not suite-safe' },

];
