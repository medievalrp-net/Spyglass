// One-shot spark cross-check: connect an op'd player, run `/spark health`, and
// print the report it sends back. spark replies asynchronously via Adventure
// messaging to the *sender* - vanilla RCON never sees that, but a real player
// (mineflayer) receives it as chat. The harness ops this name over RCON first.
//
// Env: SG_HOST, SG_PORT, SG_BOT_NAME (default SparkProbe), SG_BOT_VERSION, BOT_BASE.
import { createRequire } from 'module';

const BOT_BASE = process.env.BOT_BASE
    || '/Volumes/External-NVME/Documents/GitHub/MedievalRP/Spyglass/regression/bot/';
const require = createRequire(BOT_BASE.endsWith('/') ? BOT_BASE : BOT_BASE + '/');
const mineflayer = require('mineflayer');

const HOST = process.env.SG_HOST || '127.0.0.1';
const PORT = +(process.env.SG_PORT || 25601);
const NAME = process.env.SG_BOT_NAME || 'SparkProbe';
const VERSION = process.env.SG_BOT_VERSION || '1.21.8';
const COLLECT_MS = +(process.env.SPARK_COLLECT_MS || 9000);

const bot = mineflayer.createBot({ host: HOST, port: PORT, username: NAME, version: VERSION, auth: 'offline' });
const lines = [];
const done = (code) => { try { bot.quit(); } catch { } setTimeout(() => process.exit(code), 300); };

bot.on('messagestr', m => lines.push(m));
bot.once('spawn', async () => {
    await new Promise(r => setTimeout(r, 1500));
    bot.chat('/spark health');
    await new Promise(r => setTimeout(r, COLLECT_MS));
    // spark's tick-duration + TPS lines are the cross-check; print all captured.
    process.stdout.write(lines.join('\n') + '\n');
    done(0);
});
bot.on('error', e => { console.error('SPARKPROBE_ERR', e.message); done(2); });
bot.on('kicked', r => { console.error('SPARKPROBE_KICKED', typeof r === 'string' ? r : JSON.stringify(r)); done(2); });
setTimeout(() => { process.stdout.write(lines.join('\n') + '\n(timeout)\n'); done(0); }, COLLECT_MS + 15000);
