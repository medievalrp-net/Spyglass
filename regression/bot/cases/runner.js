// Use-case runner: executes the automatable rows of regression/use-cases.md
// against Spyglass AND CoreProtect on the live bench server, scoring each
// case pass / fail / n/a-capability / error, plus manual-deferred stubs so
// coverage is explicit. Results: JSON + markdown matrix under cases/out/.
//
//   node cases/runner.js                 # everything registered
//   node cases/runner.js --only A1,G2    # subset
//   node cases/runner.js --cat A,G,H     # categories
//
// Server must be up with both plugins (RP_Server/start-bench.sh).

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { startBot, rcon, log, sleep, PASS, FAIL, NA, ERR, MAN } from './lib.js';

import casesA from './a-blocks.js';
import casesB from './b-stateful.js';
import casesC from './c-containers.js';
import casesD from './d-entities.js';
import casesE from './e-meta.js';
import casesF from './f-environment.js';
import casesG from './g-rollback.js';
import casesH from './h-query.js';
import casesI from './i-scale.js';
import casesJ from './j-ops.js';

const ALL = [...casesA, ...casesB, ...casesC, ...casesD, ...casesE, ...casesF,
             ...casesG, ...casesH, ...casesI, ...casesJ];

const args = process.argv.slice(2);
const onlyArg = args.includes('--only') ? args[args.indexOf('--only') + 1].split(',') : null;
const catArg = args.includes('--cat') ? args[args.indexOf('--cat') + 1].split(',') : null;

const selected = ALL.filter(c =>
    (!onlyArg || onlyArg.includes(c.id)) &&
    (!catArg || catArg.includes(c.id[0])));

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(__dirname, 'out');
fs.mkdirSync(OUT_DIR, { recursive: true });

(async () => {
    log(`Use-case runner: ${selected.length} of ${ALL.length} registered cases selected.`);
    const botName = 'uc' + Date.now().toString(36).slice(-4);
    const bot = await startBot(botName);
    log(`Bot ${botName} connected.`);

    const results = [];
    for (const c of selected) {
        if (c.manual) {
            results.push({ id: c.id, title: c.title, sg: MAN, cp: MAN,
                           notes: [c.manual] });
            log(`${c.id}  MANUAL-DEFERRED — ${c.manual}`);
            continue;
        }
        log(`── ${c.id}: ${c.title}`);
        const t0 = Date.now();
        try {
            const r = await c.run(bot);
            results.push({
                id: c.id, title: c.title,
                sg: r.sg.verdict, cp: r.cp.verdict,
                sgChecks: r.sg.checks, cpChecks: r.cp.checks,
                notes: r.notes, ms: Date.now() - t0,
            });
            log(`${c.id}  SG=${r.sg.verdict}  CP=${r.cp.verdict}  (${Date.now() - t0} ms)`);
            for (const side of ['sg', 'cp']) {
                for (const ch of r[side].checks.filter(x => !x.ok)) {
                    log(`    ${side.toUpperCase()} ✗ ${ch.note}`);
                }
            }
        } catch (e) {
            results.push({ id: c.id, title: c.title, sg: ERR, cp: ERR,
                           notes: [String(e?.message || e)], ms: Date.now() - t0 });
            log(`${c.id}  ERROR: ${e?.message || e}`);
            // A wrecked bot session would cascade errors into every
            // later case — reset position and continue defensively.
            try { await rcon(`teleport ${botName} 16000 81 16000`); await sleep(1000); } catch { }
        }
    }

    // ── outputs ──────────────────────────────────────────────────
    const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
    const jsonPath = path.join(OUT_DIR, `results-${stamp}.json`);
    fs.writeFileSync(jsonPath, JSON.stringify(results, null, 2));

    const counts = side => ({
        pass: results.filter(r => r[side] === PASS).length,
        fail: results.filter(r => r[side] === FAIL).length,
        na: results.filter(r => r[side] === NA).length,
        err: results.filter(r => r[side] === ERR).length,
        man: results.filter(r => r[side] === MAN).length,
    });
    const sg = counts('sg'), cp = counts('cp');

    const md = [];
    md.push(`# Use-case run ${stamp}`);
    md.push('');
    md.push(`| | pass | fail | n/a-capability | error | manual-deferred |`);
    md.push(`|---|---|---|---|---|---|`);
    md.push(`| Spyglass | ${sg.pass} | ${sg.fail} | ${sg.na} | ${sg.err} | ${sg.man} |`);
    md.push(`| CoreProtect | ${cp.pass} | ${cp.fail} | ${cp.na} | ${cp.err} | ${cp.man} |`);
    md.push('');
    md.push(`| case | SG | CP | notes |`);
    md.push(`|---|---|---|---|`);
    for (const r of results) {
        const failNotes = [
            ...(r.sgChecks || []).filter(c => !c.ok).map(c => `SG: ${c.note}`),
            ...(r.cpChecks || []).filter(c => !c.ok).map(c => `CP: ${c.note}`),
            ...(r.notes || []),
        ].join('; ');
        md.push(`| ${r.id} ${r.title} | ${r.sg} | ${r.cp} | ${failNotes} |`);
    }
    const mdPath = path.join(OUT_DIR, `results-${stamp}.md`);
    fs.writeFileSync(mdPath, md.join('\n') + '\n');

    log('');
    log(`SG : ${sg.pass} pass, ${sg.fail} fail, ${sg.na} n/a, ${sg.err} error, ${sg.man} manual`);
    log(`CP : ${cp.pass} pass, ${cp.fail} fail, ${cp.na} n/a, ${cp.err} error, ${cp.man} manual`);
    log(`JSON: ${jsonPath}`);
    log(`MD:   ${mdPath}`);

    bot.quit();
    await sleep(1200);
    process.exit(results.some(r => r.sg === ERR || r.cp === ERR) ? 2 : 0);
})().catch(e => { log('FATAL', e?.stack || e); process.exit(2); });
