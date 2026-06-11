// Category I — scale & performance (use-cases.md I1–I8).
// The heavy benches stay in compare.js (same-run discipline, GC logs);
// here only the storage-growth case runs inline.
import { execSync } from 'child_process';
import {
    rcon, sleep, nextPlot, claimPlot, releasePlot, digAt, sgOp, coOp,
    drain, freshResult, check,
} from './lib.js';

function chCount(sql) {
    return parseInt(execSync(
        `docker exec rp-clickhouse clickhouse-client -q "${sql}"`,
        { encoding: 'utf8', timeout: 30000 }).trim(), 10);
}

export default [

{ id: 'I1', title: '2M-cube standard bench', manual: 'run regression/bot/compare.js — 2026-06-10: SG 7.7s/107ms worst tick vs CP 9.4s/288ms, both flat-20 TPS for SG' },
{ id: 'I2', title: '10M-block rollback', manual: 'heavy bench; run compare.js SIZES=10M on a dedicated session' },
{ id: 'I3', title: 'speed vs history depth', manual: 'tracked across the perf campaign: SG flat via keyset+projection at 77M→160M rows; CP variance 9.2–16.7s' },
{ id: 'I4', title: '100 sequential small rollbacks', manual: 'heavy; dedicated session' },
{ id: 'I5', title: 'rollback under bot player load', manual: 'multi-bot rig; dedicated session' },
{ id: 'I6', title: '5M-block ingest burst behavior', manual: 'queue-warning + drain measured in perf campaign; re-run per release via compare.js ingest phase' },

{
    id: 'I7', title: 'storage growth per re-run: SG +1 op row, no per-block rows',
    async run(bot) {
        const r = freshResult();
        const plot = nextPlot();
        await claimPlot(bot, plot);
        const [x, y, z] = [plot.cx + 1, plot.y, plot.cz];
        try {
            let rolledBefore, opsBefore;
            try {
                rolledBefore = chCount("SELECT count() FROM spyglass.event_records WHERE event IN ('rolled-place','rolled-break')");
                opsBefore = chCount("SELECT count() FROM spyglass.event_records WHERE event='rollback-op'");
            } catch (e) {
                r.notes.push(`ClickHouse not reachable from runner (${e.message}); counts skipped`);
                r.sg.verdict = 'error'; r.cp.verdict = 'error';
                return r;
            }
            await rcon(`setblock ${x} ${y} ${z} stone`); await sleep(400);
            await digAt(bot, x, y, z);
            await drain();
            // Three rollback+undo cycles over the same single-block grief.
            for (let i = 0; i < 3; i++) {
                await sgOp(bot, 'rollback', `p:${bot.username} t:120s r:8`);
                await sgOp(bot, 'undo', '');
            }
            await drain();
            await sleep(2000);
            const rolledAfter = chCount("SELECT count() FROM spyglass.event_records WHERE event IN ('rolled-place','rolled-break')");
            const opsAfter = chCount("SELECT count() FROM spyglass.event_records WHERE event='rollback-op'");
            const opRows = opsAfter - opsBefore;
            const rolledRows = rolledAfter - rolledBefore;
            // 6 op rows (3 rollbacks + 3 undos) and ZERO per-block
            // receipts; ambient suite events (commands, teleports) are
            // out of scope of the synthesis claim.
            check(r, 'sg', opRows === 6 && rolledRows === 0,
                `3 cycles ⇒ +${opRows} op rows, +${rolledRows} per-block receipts`,
                `unexpected growth: +${opRows} op rows, +${rolledRows} receipts`);
            r.notes.push('CP growth-per-rerun measured at the 2M scale in the perf campaign (CP re-logs rolled blocks)');
            r.cp.verdict = 'n/a-capability';
        } finally {
            await coOp(bot, 'rollback', `u:${bot.username} t:120s r:8`).catch(() => {});
            await releasePlot(plot);
        }
        return r;
    },
},

{ id: 'I8', title: 'cold-boot first-op penalty', manual: 'needs controlled reboot; perf campaign measured warm/cold pairs' },

];
