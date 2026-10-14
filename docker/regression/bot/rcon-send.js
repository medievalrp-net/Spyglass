// Minimal RCON one-shot: node rcon-send.js "<command>"
import net from 'net';
const RCON_PORT = 25576, PASS = 'test123', HOST = '127.0.0.1';
const cmd = process.argv[2] || 'list';

function packet(id, type, body) {
    const buf = Buffer.alloc(14 + body.length);
    buf.writeInt32LE(10 + body.length, 0);
    buf.writeInt32LE(id, 4);
    buf.writeInt32LE(type, 8);
    buf.write(body, 12, 'ascii');
    return buf;
}

const s = net.createConnection({ host: HOST, port: RCON_PORT, timeout: 30000 });
let auth = false;
s.on('connect', () => s.write(packet(0x1337, 3, PASS)));
s.on('data', (d) => {
    if (!auth) {
        auth = true;
        s.write(packet(0x2001, 2, cmd));
    } else {
        const body = d.slice(12, d.length - 2).toString('ascii');
        if (body.trim()) process.stdout.write(body + '\n');
        s.end();
    }
});
s.on('timeout', () => { s.destroy(); process.exit(1); });
s.on('error', (e) => { console.error('rcon error:', e.message); process.exit(1); });
s.on('close', () => process.exit(0));
