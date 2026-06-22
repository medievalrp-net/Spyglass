// Idle actor bot for the ingest benchmark. Its only job is to be an online
// Player so IngestBench can fire real Block break/place events "as" it. It does
// not move, dig, or place anything itself - the plugin fires synthetic events
// through the plugin manager. mineflayer 4.37 speaks 1.21.8 natively, so no
// ViaVersion is needed.
//
// The worktree has no node_modules, so we resolve mineflayer from the primary
// checkout's regression/bot via createRequire (CommonJS resolution, which -
// unlike ESM/NODE_PATH - honours an explicit base path).
//
// Env: SG_HOST, SG_PORT, SG_BOT_NAME, SG_BOT_VERSION, BOT_BASE (dir containing
// node_modules). Prints "ACTOR_SPAWNED" on spawn; reconnects on disconnect.
import { createRequire } from 'module';

const BOT_BASE = process.env.BOT_BASE
    || '/Volumes/External-NVME/Documents/GitHub/MedievalRP/Spyglass/regression/bot/';
const require = createRequire(BOT_BASE.endsWith('/') ? BOT_BASE : BOT_BASE + '/');
const mineflayer = require('mineflayer');

const HOST = process.env.SG_HOST || '127.0.0.1';
const PORT = +(process.env.SG_PORT || 25601);
const NAME = process.env.SG_BOT_NAME || 'IngestActor';
const VERSION = process.env.SG_BOT_VERSION || '1.21.8';

let bot = null;
let stopping = false;

function log(...a) {
    console.log('[' + new Date().toISOString().slice(11, 19) + ']', ...a);
}

function connect() {
    if (stopping) return;
    log(`connecting ${NAME} -> ${HOST}:${PORT} (${VERSION})`);
    bot = mineflayer.createBot({
        host: HOST,
        port: PORT,
        username: NAME,
        version: VERSION,
        auth: 'offline',
        // Long keepalive: a big GC pause at a high event rate must not drop us.
        checkTimeoutInterval: 600_000,
    });

    bot.once('spawn', () => {
        log('ACTOR_SPAWNED');
        // Stand still; never get kicked for idling on a flat platform.
        try { bot.setControlState('forward', false); } catch { }
    });
    bot.on('death', () => { try { bot.respawn(); } catch { } });
    bot.on('kicked', r => log('kicked:', typeof r === 'string' ? r : JSON.stringify(r)));
    bot.on('error', e => log('error:', e.message));
    bot.on('end', reason => {
        log('end:', reason);
        if (!stopping) setTimeout(connect, 2000); // reconnect mid-run
    });
}

function shutdown() {
    stopping = true;
    log('shutdown');
    try { bot && bot.quit(); } catch { }
    setTimeout(() => process.exit(0), 500);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

connect();
