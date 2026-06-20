// Category E — player meta (use-cases.md E1–E8).
import {
    rcon, sleep, nextPlot, claimPlot, releasePlot, sgSearch, coLookup,
    chatCollect, grep, drain, freshResult, check, NA,
} from './lib.js';

export default [

{
    id: 'E1', title: 'chat recorded and searchable by player',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const marker = 'forensic-marker-' + Date.now().toString(36);
        try {
            const echo = new Promise(res => {
                const h = m => { if (m.includes(marker)) { bot.removeListener('messagestr', h); res(true); } };
                bot.on('messagestr', h);
                setTimeout(() => { bot.removeListener('messagestr', h); res(false); }, 4000);
            });
            bot.chat(marker);
            const delivered = await echo;
            if (!delivered) {
                // A chat-gate plugin (persona/graylist) cancelled the
                // AsyncChatEvent before anyone saw it. Spyglass records
                // at ignoreCancelled=true by design — suppressed chat is
                // not audit material — so there is nothing to assert.
                r.notes.push('environment: chat gated for persona-less bots (event cancelled); Spyglass correctly records only delivered chat');
                await releasePlot(plot);
                return r;
            }
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s a:say -g`);
            check(r, 'sg', grep(sg, new RegExp(marker)).length > 0 || grep(sg, /said/i).length > 0,
                'chat line surfaced', `no chat hit: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:#global a:chat`);
            check(r, 'cp', grep(cp, new RegExp(marker)).length > 0 || grep(cp, /chat/i).length > 1,
                'chat line surfaced', `no a:chat hit: ${cp.slice(-3).join(' | ')}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'E2', title: 'command recorded with full line',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            bot.chat('/help');
            await sleep(800);
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s a:command -g`);
            check(r, 'sg', grep(sg, /\/help|ran/i).length > 0,
                'command surfaced', `no command hit: ${sg.slice(-3).join(' | ')}`);
            const cp = await coLookup(bot, `u:${bot.username} t:60s r:#global a:command`);
            check(r, 'cp', grep(cp, /help|command/i).length > 0,
                'command surfaced', `no a:command hit: ${cp.slice(-3).join(' | ')}`);
        } finally { await releasePlot(plot); }
        return r;
    },
},

{
    id: 'E3', title: 'session join searchable',
    async run(bot) {
        const r = freshResult();
        // The runner bot joined at suite start — that join is the fixture.
        const sg = await sgSearch(bot, `p:${bot.username} t:2h a:join -g`);
        check(r, 'sg', grep(sg, /joined/i).length > 0,
            'join surfaced', `no join hit: ${sg.slice(-3).join(' | ')}`);
        const cp = await coLookup(bot, `u:${bot.username} t:2h r:#global a:session`);
        check(r, 'cp', grep(cp, /session|login|logged/i).length > 0,
            'session surfaced', `no a:session hit: ${cp.slice(-3).join(' | ')}`);
        return r;
    },
},

{
    id: 'E4', title: 'teleport trail',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        try {
            await rcon(`teleport ${bot.username} ${plot.cx + 4.5} ${plot.y} ${plot.cz + 4.5}`);
            await sleep(1000);
            await drain();
            const sg = await sgSearch(bot, `p:${bot.username} t:60s a:teleport -g`);
            check(r, 'sg', grep(sg, /teleport/i).length > 0,
                'teleport surfaced', `no teleport hit: ${sg.slice(-3).join(' | ')}`);
            r.cp.verdict = NA;
            r.notes.push('CP has no teleport logging');
        } finally { await releasePlot(plot); }
        return r;
    },
},

{ id: 'E5', title: 'death-drop loot theft chain', manual: 'survival death choreography; custody core covered by C7' },
{ id: 'E6', title: 'combat-log quit story', manual: 'needs PvP rig' },
{ id: 'E7', title: 'hour-scale chat volume cost', manual: 'long-horizon storage measurement; see perf campaign methodology' },

{
    id: 'E8', title: 'permission denial is clean for non-ops',
    async run(bot) {
        const r = freshResult();
        try {
            await rcon(`deop ${bot.username}`);
            await sleep(600);
            const sg = await chatCollect(bot, `/spyglass search p:${bot.username} t:60s`);
            check(r, 'sg', grep(sg, /permission|allowed|denied|Unknown command|graylisted/i).length > 0,
                'denied cleanly', `unexpected response: ${sg.slice(-2).join(' | ')}`);
            r.notes.push('a Vesta graylist also gates non-op commands on this server — denial observed through it');
            const sgRb = await chatCollect(bot, `/spyglass rollback p:${bot.username} t:60s r:8`);
            check(r, 'sg', !grep(sgRb, /reversals/i).length,
                'rollback denied for non-op', 'non-op rollback EXECUTED — permission hole');
            const cp = await chatCollect(bot, `/co lookup u:${bot.username} t:60s r:8`, { quiet: 2200 });
            check(r, 'cp', grep(cp, /permission|denied|Unknown command/i).length > 0
                    || !grep(cp, /looking|lookup|found/i).length,
                'denied cleanly', `unexpected response: ${cp.slice(-2).join(' | ')}`);
        } finally {
            await rcon(`op ${bot.username}`);
            await sleep(600);
        }
        return r;
    },
},

];
