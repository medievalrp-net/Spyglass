// Category J — operations & resilience (use-cases.md J1–J8).
import {
    rcon, sleep, chatCollect, grep, freshResult, check,
} from './lib.js';

export default [

{ id: 'J1', title: 'DB down during play (WAL durability)', manual: 'container kill orchestration; WAL replay is IT-covered (DurabilityIT) — staging drill per release' },
{ id: 'J2', title: 'DB dies mid-rollback', manual: 'container kill orchestration; staging drill' },
{ id: 'J3', title: 'retention + undo TTL expiry', manual: 'needs >24h horizon or clock control' },
{ id: 'J4', title: 'full suite on Mongo backend', manual: 'backend swap + reboot + rerun: config database.backend=mongo, then runner.js --cat A,B,C,G,H' },
{ id: 'J5', title: 'kill -9 mid-burst, WAL replay dedup', manual: 'crashtest.js exists for this; not suite-safe inline' },
{ id: 'J6', title: 'two servers, one store, srv: partitioning', manual: 'needs second server instance' },
{ id: 'J7', title: 'pre-#22 persisted receipts still searchable', manual: 'needs legacy-store fixture; decode-compat unit-tested (UndoReferenceBson v1, receipts mode IT)' },

{
    id: 'J8', title: 'permission matrix: search/rollback/tool gated for non-ops',
    async run(bot) {
        const r = freshResult();
        try {
            await rcon(`deop ${bot.username}`);
            await sleep(600);
            const search = await chatCollect(bot, `/spyglass search p:${bot.username} t:60s`);
            const rollback = await chatCollect(bot, `/spyglass rollback p:${bot.username} t:60s r:8`);
            const tool = await chatCollect(bot, '/spyglass tool');
            const sgDenied =
                !grep(search, /placed|broke|results? \d/i).length &&
                !grep(rollback, /reversals/i).length &&
                !grep(tool, /enabled|bound/i).length;
            check(r, 'sg', sgDenied, 'all three commands denied',
                `leak: search=${search.slice(-1)} rollback=${rollback.slice(-1)} tool=${tool.slice(-1)}`);
            const cpLookup = await chatCollect(bot, `/co lookup u:${bot.username} t:60s r:8`, { quiet: 2200 });
            const cpRb = await chatCollect(bot, `/co rollback u:${bot.username} t:60s r:8`, { quiet: 2200 });
            const cpDenied = !grep(cpLookup, /found|ago/i).length && !grep(cpRb, /Rollback (started|completed)/i).length;
            check(r, 'cp', cpDenied, 'lookup and rollback denied',
                `leak: lookup=${cpLookup.slice(-1)} rollback=${cpRb.slice(-1)}`);
        } finally {
            await rcon(`op ${bot.username}`);
            await sleep(600);
        }
        return r;
    },
},

];
