// Category B — stateful / multi-block (use-cases.md B1–B12).
import {
    rcon, sleep, blockIs, blockAmong, blockData, nextPlot, claimPlot,
    releasePlot, digAt, sgOp, coOp, drain, freshResult, check,
} from './lib.js';

export default [

{ id: 'B1', title: 'sign with styled text broken + restored', manual: 'sign text entry needs the sign-editor packet flow' },
{ id: 'B2', title: 'sign text edited, edit rolls back', manual: 'sign-editor packet flow' },

{
    id: 'B3', title: 'chest with named+enchanted loot restores exactly',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} chest`);
            await rcon(`data merge block ${x} ${y} ${z} {Items:[`
                + `{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":'"Excaliblur"',"minecraft:enchantments":{"minecraft:sharpness":5}}},`
                + `{Slot:13b,id:"minecraft:golden_apple",count:7}]}`);
            await sleep(600);
            await digAt(bot, x, y, z);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            // data get truncates long displayed NBT — assert precise paths.
            const sgSword = await blockData(x, y, z, 'Items[0].components');
            const sgApple = await blockData(x, y, z, 'Items[1]');
            check(r, 'sg',
                /Excaliblur/.test(sgSword) && /sharpness/.test(sgSword)
                    && /golden_apple/.test(sgApple) && /Slot:\s*13/.test(sgApple),
                'name, enchant, slots all back',
                `restored: sword=${sgSword.slice(-120)} apple=${sgApple.slice(-90)}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            const cpSword = await blockData(x, y, z, 'Items[0].components');
            const cpApple = await blockData(x, y, z, 'Items[1]');
            check(r, 'cp',
                /Excaliblur/.test(cpSword) && /sharpness/.test(cpSword) && /golden_apple/.test(cpApple),
                'CP restored full NBT', `CP restored: sword=${cpSword.slice(-120)} apple=${cpApple.slice(-90)}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'B4', title: 'double chest: both halves + contents, no duplication',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const y = plot.y, z = plot.cz;
        const xL = plot.cx + 1, xR = plot.cx + 2;
        try {
            await rcon(`setblock ${xL} ${y} ${z} chest[type=right,facing=south]`);
            await rcon(`setblock ${xR} ${y} ${z} chest[type=left,facing=south]`);
            await rcon(`data merge block ${xL} ${y} ${z} {Items:[{Slot:0b,id:"minecraft:emerald",count:11}]}`);
            await rcon(`data merge block ${xR} ${y} ${z} {Items:[{Slot:0b,id:"minecraft:lapis_lazuli",count:22}]}`);
            await sleep(600);
            await digAt(bot, xL, y, z);
            await digAt(bot, xR, y, z);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            const left = await blockData(xL, y, z, 'Items');
            const right = await blockData(xR, y, z, 'Items');
            check(r, 'sg',
                /emerald/.test(left) && /lapis/.test(right)
                    && !/lapis/.test(left) && !/emerald/.test(right),
                'both halves restored with their own contents',
                `left=${left.slice(0, 90)} right=${right.slice(0, 90)}`);
            await sgOp(bot, 'undo', '');
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            const cpLeft = await blockData(xL, y, z, 'Items');
            const cpRight = await blockData(xR, y, z, 'Items');
            check(r, 'cp', /emerald/.test(cpLeft) && /lapis/.test(cpRight),
                'CP restored both halves', `CP left=${cpLeft.slice(0, 90)} right=${cpRight.slice(0, 90)}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{ id: 'B5', title: 'shulker box with items', manual: 'shulker pickup/contents flow needs inventory choreography' },
{ id: 'B6', title: 'banner with patterns', manual: 'pattern NBT round-trip needs visual/NBT-deep verify' },
{ id: 'B7', title: 'jukebox with disc', manual: 'disc insert via right-click choreography' },
{ id: 'B8', title: 'decorated pot sherds', manual: 'pot insert/remove choreography' },
{ id: 'B9', title: 'chiseled bookshelf slots', manual: 'slot-targeted clicks needed' },

{
    id: 'B10', title: 'bed: breaking one half restores both halves',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const y = plot.y, z = plot.cz;
        const xFoot = plot.cx + 1, xHead = plot.cx + 2;
        try {
            await rcon(`setblock ${xFoot} ${y} ${z} red_bed[part=foot,facing=east]`);
            await rcon(`setblock ${xHead} ${y} ${z} red_bed[part=head,facing=east]`);
            await sleep(600);
            await digAt(bot, xFoot, y, z);   // pops both halves
            await sleep(600);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            const foot = await blockIs(xFoot, y, z, 'red_bed[part=foot,facing=east]');
            const head = await blockIs(xHead, y, z, 'red_bed[part=head,facing=east]');
            check(r, 'sg', foot && head, 'both bed halves restored',
                `foot=${foot} head=${head} — half-bed restore is a real bug`);
            await sgOp(bot, 'undo', '');
            await rcon(`fill ${xFoot} ${y} ${z} ${xHead} ${y} ${z} air`);
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            const cpFoot = await blockIs(xFoot, y, z, 'red_bed[part=foot,facing=east]');
            const cpHead = await blockIs(xHead, y, z, 'red_bed[part=head,facing=east]');
            check(r, 'cp', cpFoot && cpHead, 'CP restored both halves',
                `CP foot=${cpFoot} head=${cpHead}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'B11', title: 'door: breaking lower half restores the pair',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} oak_door[half=lower,facing=east]`);
            await rcon(`setblock ${x} ${y + 1} ${z} oak_door[half=upper,facing=east]`);
            await sleep(600);
            await digAt(bot, x, y, z);
            await sleep(600);
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            const lower = await blockIs(x, y, z, 'oak_door[half=lower,facing=east]');
            const upper = await blockIs(x, y + 1, z, 'oak_door[half=upper,facing=east]');
            check(r, 'sg', lower && upper, 'both door halves restored',
                `lower=${lower} upper=${upper}`);
            await sgOp(bot, 'undo', '');
            await rcon(`fill ${x} ${y} ${z} ${x} ${y + 1} ${z} air`);
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            check(r, 'cp',
                (await blockIs(x, y, z, 'oak_door[half=lower,facing=east]'))
                    && (await blockIs(x, y + 1, z, 'oak_door[half=upper,facing=east]')),
                'CP restored the pair', 'CP door halves wrong');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'B12', title: 'waterlogged stairs keep waterlogged=true through rollback',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            await rcon(`setblock ${x} ${y} ${z} oak_stairs[facing=east,waterlogged=true]`);
            await sleep(600);
            await digAt(bot, x, y, z);
            await sleep(400);
            await rcon(`setblock ${x} ${y} ${z} air`);  // clear the leftover water
            await drain();
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`);
            check(r, 'sg', await blockIs(x, y, z, 'oak_stairs[facing=east,waterlogged=true]'),
                'waterlogged state restored',
                `state=${await blockAmong(x, y, z, ['oak_stairs[facing=east,waterlogged=true]', 'oak_stairs', 'water', 'air'])}`);
            await sgOp(bot, 'undo', '');
            await rcon(`setblock ${x} ${y} ${z} air`);
            await coOp(bot, 'rollback', `u:${bot.username} t:60s r:8`);
            check(r, 'cp', await blockIs(x, y, z, 'oak_stairs[facing=east,waterlogged=true]'),
                'CP restored waterlogged', 'CP lost waterlogged state');
        } finally { await releasePlot(plot); }
        return r;
    },
},

];
