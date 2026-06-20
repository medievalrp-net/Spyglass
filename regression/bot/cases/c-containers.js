// Category C — containers & item flow (use-cases.md C1–C14).
import {
    rcon, sleep, blockData, nextPlot, claimPlot, releasePlot, give,
    sgSearch, coLookup, sgOp, coOp, grep, drain, freshResult, check, startBot,
} from './lib.js';
import { Vec3 } from 'vec3';

async function openChest(bot, x, y, z) {
    const block = bot.blockAt(new Vec3(x, y, z));
    if (!block || block.name !== 'chest') throw new Error(`no chest at ${x},${y},${z}`);
    return bot.openContainer(block);
}

export default [

{
    id: 'C1', title: 'chest deposit: record + container rollback both sides',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} chest`); await sleep(600);
            await give(bot, 'iron_ingot', 64);
            const chest = await openChest(bot, x, y, z);
            const iron = bot.registry.itemsByName.iron_ingot.id;
            await chest.deposit(iron, null, 64);
            await sleep(600);
            chest.close(); await sleep(600);
            await drain();

            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 a:deposit`);
            check(r, 'sg', grep(sg, /deposit/i).some(l => /iron/i.test(l)),
                'deposit recorded', `no deposit hit: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 a:container`);
            check(r, 'cp', grep(cp, /iron/i).length > 0,
                'deposit recorded', `no container hit: ${cp.slice(-3).join(' | ')}`);

            const rbDep = await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8 a:deposit`);
            r.notes.push(`C1 SG rollback line: ${rbDep.line.trim()}`);
            const afterSg = await blockData(x, y, z, 'Items');
            check(r, 'sg', !/iron_ingot/.test(afterSg),
                'rollback pulled the deposit back out', `chest still holds: ${afterSg.slice(0, 100)}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8 a:container`);
            const afterCp = await blockData(x, y, z, 'Items');
            check(r, 'cp', !/iron_ingot/.test(afterCp),
                'CP pulled the deposit back out', `CP left: ${afterCp.slice(0, 100)}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'C2', title: 'chest withdraw: rollback puts the loot back',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} chest`);
            await rcon(`data merge block ${x} ${y} ${z} {Items:[{Slot:0b,id:"minecraft:gold_ingot",count:32}]}`);
            await sleep(600);
            const chest = await openChest(bot, x, y, z);
            const gold = bot.registry.itemsByName.gold_ingot.id;
            await chest.withdraw(gold, null, 32);
            await sleep(600);
            chest.close(); await sleep(600);
            await drain();

            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 a:withdraw`);
            check(r, 'sg', grep(sg, /withdrew|withdraw/i).some(l => /gold/i.test(l)),
                'withdraw recorded', `no withdraw hit: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 a:container`);
            check(r, 'cp', grep(cp, /gold/i).length > 0, 'withdraw recorded', 'no container hit');

            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8 a:withdraw`);
            const afterSg = await blockData(x, y, z, 'Items');
            check(r, 'sg', /gold_ingot/.test(afterSg) && /32/.test(afterSg),
                'rollback returned 32 gold to the chest', `chest holds: ${afterSg.slice(0, 100) || '(empty)'}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8 a:container`);
            const afterCp = await blockData(x, y, z, 'Items');
            check(r, 'cp', /gold_ingot/.test(afterCp),
                'CP returned the gold', `CP chest holds: ${afterCp.slice(0, 100) || '(empty)'}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'C3', title: 'open→deposit→withdraw→close session ordering',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} chest`); await sleep(600);
            await give(bot, 'emerald', 16);
            const chest = await openChest(bot, x, y, z);
            const emerald = bot.registry.itemsByName.emerald.id;
            await chest.deposit(emerald, null, 16); await sleep(500);
            await chest.withdraw(emerald, null, 8); await sleep(500);
            chest.close(); await sleep(600);
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8`);
            const hasOpen = grep(sg, /opened/i).length > 0;
            const hasDeposit = grep(sg, /deposited/i).length > 0;
            const hasWithdraw = grep(sg, /withdrew/i).length > 0;
            const hasClose = grep(sg, /closed/i).length > 0;
            check(r, 'sg', hasOpen && hasDeposit && hasWithdraw && hasClose,
                'open/deposit/withdraw/close all recorded',
                `open=${hasOpen} deposit=${hasDeposit} withdraw=${hasWithdraw} close=${hasClose}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 a:container`);
            check(r, 'cp', grep(cp, /emerald/i).length >= 1,
                'transactions recorded (CP groups same-item rows)',
                `container lines: ${grep(cp, /emerald/i).length}`);
            r.notes.push('CP records container transactions only — no open/close events (SG-extra forensics)');
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8 a:deposit,withdraw`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{ id: 'C4', title: 'hopper drains a chest: automation attribution', manual: 'hopper timing flaky in suite; verify on staging with a:withdraw + cause filters' },
{ id: 'C5', title: 'hopper minecart attribution', manual: 'needs cart choreography' },
{ id: 'C6', title: 'dropper fires into chest', manual: 'needs redstone choreography' },

{
    id: 'C7', title: 'drop → pickup custody chain across two players',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const botB = await startBot('ucc7' + Date.now().toString(36).slice(-2));
        try {
            await give(bot, 'diamond', 5);
            const stack = bot.inventory.items().find(i => i.name === 'diamond');
            await bot.tossStack(stack);
            await sleep(700);
            // Move A away so it can't reclaim its own toss, then put B
            // exactly on the item once the pickup delay has elapsed.
            const item = bot.nearestEntity(e => e.name === 'item' || e.entityType === 'item');
            const ip = item ? item.position : { x: plot.cx + 0.5, y: plot.y, z: plot.cz + 0.5 };
            await rcon(`teleport ${bot.username} ${plot.cx + 30.5} ${plot.y + 4} ${plot.cz + 0.5}`);
            await sleep(2200);  // outlive the toss pickup-delay
            await rcon(`teleport ${botB.username} ${ip.x} ${ip.y} ${ip.z}`);
            await sleep(2500);
            await rcon(`teleport ${bot.username} ${plot.cx + 0.5} ${plot.y} ${plot.cz + 0.5}`);
            await sleep(800);
            await drain();
            const sg = await sgSearch(bot, `t:60s r:8 a:drop,pickup`);
            const dropped = grep(sg, /dropped/i).some(l => new RegExp(bot.username).test(l));
            const picked = grep(sg, /picked/i).some(l => new RegExp(botB.username).test(l));
            check(r, 'sg', dropped && picked,
                `custody chain ${bot.username}→${botB.username} reconstructed`,
                `drop=${dropped} pickup=${picked}: ${sg.slice(-4).join(' | ')}`);
            const cp = await coLookup(bot, `t:60s r:8 a:item`);
            check(r, 'cp', grep(cp, /diamond/i).length >= 1,
                'item transactions visible', `a:item lines: ${grep(cp, /diamond/i).length}`);
        } finally {
            botB.quit(); await sleep(800);
            await releasePlot(plot);
        }
        return r;
    },
},

{ id: 'C8', title: 'item frame place/rotate/remove', manual: 'entity right-click choreography' },
{ id: 'C9', title: 'armor stand equip/strip', manual: 'entity interaction choreography' },
{ id: 'C10', title: 'bundle insert/extract', manual: 'inventory-GUI bundle clicks not bot-scriptable; SG events exist, CP expected N/A' },
{ id: 'C11', title: 'crafter crafts', manual: 'needs redstone pulse rig' },
{ id: 'C12', title: 'trial vault unlock', manual: 'needs trial chamber + key' },
{ id: 'C13', title: 'suspicious sand brush', manual: 'brush choreography' },
{ id: 'C14', title: 'lectern book place/take', manual: 'lectern GUI choreography' },

];
