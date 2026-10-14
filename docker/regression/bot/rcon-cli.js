// Minimal one-shot RCON client: `node rcon-cli.js "<command>"` prints the
// server's response. Used to drive versions no mineflayer build supports (26.x)
// entirely over RCON. Ports via env (SG_RCON_PORT / SG_RCON_PASS / SG_HOST).
import net from 'net';

const HOST = process.env.SG_HOST || '127.0.0.1';
const PORT = +(process.env.SG_RCON_PORT || 25579);
const PASS = process.env.SG_RCON_PASS || 'test123';
const cmd = process.argv.slice(2).join(' ');

function pkt(id, type, body) {
    const b = Buffer.from(body, 'utf8');
    const o = Buffer.alloc(14 + b.length);
    o.writeInt32LE(10 + b.length, 0);
    o.writeInt32LE(id, 4);
    o.writeInt32LE(type, 8);
    b.copy(o, 12);
    o.writeInt16LE(0, 12 + b.length);
    return o;
}

const s = net.createConnection({ host: HOST, port: PORT, timeout: 30000 });
let st = 0, buf = Buffer.alloc(0), out = [], quiet = null;
s.on('error', e => { console.error('RCONERR', e.message); process.exit(3); });
s.on('timeout', () => { s.destroy(); console.error('RCON timeout'); process.exit(3); });
s.on('connect', () => s.write(pkt(0x1337, 3, PASS)));
s.on('data', c => {
    buf = Buffer.concat([buf, c]);
    while (buf.length >= 4) {
        const len = buf.readInt32LE(0);
        if (buf.length < len + 4) break;
        const id = buf.readInt32LE(4);
        const body = buf.slice(12, 12 + len - 10).toString('utf8').replace(/§./g, '');
        buf = buf.slice(len + 4);
        if (st === 0) {
            if (id === -1) { console.error('auth fail'); process.exit(3); }
            st = 1; s.write(pkt(0x1337, 2, cmd));
        } else {
            out.push(body);
            if (quiet) clearTimeout(quiet);
            quiet = setTimeout(() => { s.end(); process.stdout.write(out.join('')); process.exit(0); }, +(process.env.SG_RCON_QUIET || 350));
        }
    }
});
