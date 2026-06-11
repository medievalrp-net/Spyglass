// Category H — query & search capability (use-cases.md H1–H12).
import {
    rcon, sleep, nextPlot, claimPlot, releasePlot, placeAt, digAt, give,
    sgSearch, coLookup, sgOp, coOp, chatCollect, grep, drain,
    freshResult, check, NA,
} from './lib.js';

export default [

{
    id: 'H1', title: 'p:+t:+r: triple returns the incident on both sides',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await placeAt(bot, 'dirt', plot.cx + 1, plot.y, plot.cz);
            await rcon(`setblock ${plot.cx + 3} ${plot.y} ${plot.cz} stone`); await sleep(400);
            await digAt(bot, plot.cx + 3, plot.y, plot.cz);
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8`);
            check(r, 'sg', grep(sg, /placed|broke/i).length >= 2,
                'both events surfaced', `events found: ${grep(sg, /placed|broke/i).length}/2`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8`);
            check(r, 'cp', grep(cp, /dirt|stone/i).length >= 2,
                'both events surfaced', `events found: ${grep(cp, /dirt|stone/i).length}/2`);
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'H2', title: 'pagination across a ~2000-event result set',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await rcon(`fill ${plot.x0 + 3} ${plot.y} ${plot.z0 + 3} ${plot.x0 + 12} ${plot.y + 19} ${plot.z0 + 12} stone`);
            await sleep(800);
            bot.chat('//sel cuboid'); await sleep(250);
            bot.chat(`//pos1 ${plot.x0 + 3},${plot.y},${plot.z0 + 3}`); await sleep(250);
            bot.chat(`//pos2 ${plot.x0 + 12},${plot.y + 19},${plot.z0 + 12}`); await sleep(250);
            bot.chat('//set air'); await sleep(2500);
            await drain();
            const sg1 = await sgSearch(bot, `p:${bot.username} t:120s r:24`);
            const sg2 = await chatCollect(bot, '/spyglass page 2');
            check(r, 'sg', sg1.length > 3 && sg2.length > 1 && !grep(sg2, /No results|error/i).length,
                'page 2 served', `page1=${sg1.length} lines, page2=${sg2.length} lines`);
            const cp1 = await coLookup(bot, `u:${bot.username} t:120s r:24`);
            const cp2 = await chatCollect(bot, '/co lookup 2', { quiet: 2500 });
            check(r, 'cp', cp1.length > 3 && cp2.length > 1 && !grep(cp2, /No results/i).length,
                'page 2 served', `page1=${cp1.length} lines, page2=${cp2.length} lines`);
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:120s r:24`).catch(() => {});
            await sgOp(bot, 'undo', '').catch(() => {});
            await coOp(bot, 'rollback', `u:${bot.username} t:120s r:24`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'H3', title: 'search by item name (iname:)',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await rcon(`give ${bot.username} minecraft:diamond_sword[minecraft:custom_name='"Excaliblur"'] 1`);
            await sleep(800);
            const sword = bot.inventory.items().find(i => i.name === 'diamond_sword');
            check(r, 'sg', !!sword, 'named sword in inventory', 'give with custom_name failed');
            if (sword) { await bot.tossStack(sword); await sleep(800); }
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 iname:Excaliblur`);
            check(r, 'sg', grep(sg, /dropped|Excaliblur|diamond_sword|sword/i).length > 0,
                'iname: matched the drop', `no iname hit: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 iname:Excaliblur`);
            r.cp.verdict = NA;
            r.notes.push(`CP has no item-name filter (response: ${cp.slice(-1)[0] || 'none'})`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'H4', title: 'search by item lore (ilore:)',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await rcon(`give ${bot.username} minecraft:iron_sword[minecraft:lore=['"Forged in primordial fire"']] 1`);
            await sleep(800);
            const sword = bot.inventory.items().find(i => i.name === 'iron_sword');
            check(r, 'sg', !!sword, 'lored sword in inventory', 'give with lore failed');
            if (sword) { await bot.tossStack(sword); await sleep(800); }
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 ilore:primordial`);
            check(r, 'sg', grep(sg, /dropped|iron_sword|sword/i).length > 0,
                'ilore: matched the drop', `no ilore hit: ${sg.slice(-3).join(' | ')}`);
            r.cp.verdict = NA;
            r.notes.push('CP has no lore filter');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'H5', title: 'search by enchantment (ench:)',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await rcon(`give ${bot.username} minecraft:netherite_sword[minecraft:enchantments={"minecraft:sharpness":5}] 1`);
            await sleep(800);
            const sword = bot.inventory.items().find(i => i.name === 'netherite_sword');
            check(r, 'sg', !!sword, 'enchanted sword in inventory', 'give with enchantments failed');
            if (sword) { await bot.tossStack(sword); await sleep(800); }
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 ench:sharpness=5`);
            check(r, 'sg', grep(sg, /dropped|netherite_sword|sword/i).length > 0,
                'ench: matched the drop', `no ench hit: ${sg.slice(-3).join(' | ')}`);
            r.cp.verdict = NA;
            r.notes.push('CP has no enchantment filter');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'H6', title: 'multi-player filter p:a,b',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await placeAt(bot, 'dirt', plot.cx + 1, plot.y, plot.cz);
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username},Notch t:60s r:8`);
            check(r, 'sg', grep(sg, /placed/i).length > 0 && !grep(sg, /requires|error|bad/i).length,
                'multi-value p: accepted and matched', `multi-p failed: ${sg.slice(-2).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username},Notch t:60s r:8`);
            check(r, 'cp', grep(cp, /dirt/i).length > 0,
                'multi-value u: accepted and matched', `multi-u failed: ${cp.slice(-2).join(' | ')}`);
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'H7', title: 'negation filter excludes an action',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await placeAt(bot, 'dirt', plot.cx + 1, plot.y, plot.cz);
            await rcon(`setblock ${plot.cx + 3} ${plot.y} ${plot.cz} stone`); await sleep(400);
            await digAt(bot, plot.cx + 3, plot.y, plot.cz);
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8 a:!place`);
            check(r, 'sg', grep(sg, /broke/i).length > 0 && grep(sg, /placed/i).length === 0,
                'a:!place returned breaks only', `negation leak: ${sg.slice(-4).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8 e:dirt`);
            check(r, 'cp', grep(cp, /stone/i).length > 0 && grep(cp, /dirt/i).length === 0,
                'e:dirt excluded the place', `CP exclusion leak: ${cp.slice(-4).join(' | ')}`);
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{ id: 'H8', title: 'cross-server search via Velocity proxy', manual: 'needs the proxy rig; spyglass-velocity covered by its own tests' },
{ id: 'H9', title: 'wand/inspector parity on a grief block', manual: 'wand interaction needs a human (or dedicated packet work)' },

{
    id: 'H10', title: 'time syntax: t:30s and compound t:1d2h parse and bound',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await placeAt(bot, 'dirt', plot.cx + 1, plot.y, plot.cz);
            await drain();
            const sgA = await sgSearch(bot, `p:${bot.username} t:30s r:8`);
            const sgB = await sgSearch(bot, `p:${bot.username} t:1d2h r:8`);
            check(r, 'sg',
                grep(sgA, /placed/i).length > 0 && grep(sgB, /placed/i).length > 0
                    && !grep(sgB, /bad|error|requires/i).length,
                'both syntaxes parse and match', `t-syntax issue: ${sgB.slice(-2).join(' | ')}`);
            const cpA = await coLookup(bot, `u:${bot.username} t:30s r:8`);
            const cpB = await coLookup(bot, `u:${bot.username} t:1d2h r:8`);
            check(r, 'cp', grep(cpA, /dirt/i).length > 0 && grep(cpB, /dirt/i).length > 0,
                'both syntaxes parse and match', 'CP t-syntax issue');
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'H11', title: 'default radius reminder + -g global flag',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await placeAt(bot, 'dirt', plot.cx + 1, plot.y, plot.cz);
            await drain();
            const noR = await sgSearch(bot, `p:${bot.username} t:60s`);
            check(r, 'sg', grep(noR, /placed/i).length > 0,
                'r-less search works (default radius applied)', 'r-less search returned nothing');
            const g = await sgSearch(bot, `p:${bot.username} t:60s -g`);
            check(r, 'sg', grep(g, /placed/i).length > 0, '-g global search works',
                `-g failed: ${g.slice(-3).join(' | ') || '(no output)'}`);
            const cpG = await coLookup(bot, `u:${bot.username} t:60s r:#global`);
            check(r, 'cp', grep(cpG, /dirt/i).length > 0, 'r:#global works', 'CP global failed');
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{
    id: 'H12', title: 'search latency on the ~160M-row store (informational)',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await placeAt(bot, 'dirt', plot.cx + 1, plot.y, plot.cz);
            await drain();
            const t0 = Date.now();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s r:8`);
            const sgMs = Date.now() - t0;
            const t1 = Date.now();
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:8`);
            const cpMs = Date.now() - t1;
            r.notes.push(`lookup latency (incl. ~2s output-quiet window): SG ${sgMs}ms, CP ${cpMs}ms`);
            check(r, 'sg', grep(sg, /placed/i).length > 0, `served in ${sgMs}ms`, 'no result');
            check(r, 'cp', grep(cp, /dirt/i).length > 0, `served in ${cpMs}ms`, 'no result');
        } finally {
            await sgOp(bot, 'rollback', `p:${bot.username} t:60s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

];
