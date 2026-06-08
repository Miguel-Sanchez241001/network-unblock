const http = require('http');
const https = require('https');
const net = require('net');

// ══════════════════════════════════════════════════════════════
//  AI PROXY v1.0 - Bypass DNS + SNI blocking
//  Soporta: ChatGPT (OpenAI) y Claude (Anthropic)
//  1. Resuelve dominios AI a IPs directas (via Google DNS-over-HTTPS)
//  2. Fragmenta TLS ClientHello para evadir DPI/SNI inspection
// ══════════════════════════════════════════════════════════════

const PROXY_PORT = 8889;

// Poner en false para probar sin fragmentacion TLS (diagnostico)
const SPLIT_ENABLED = process.env.NO_SPLIT !== '1';

// IPs pre-resueltas via dns.google (evita depender del DNS del router en el arranque)
// Cloudflare IPs para OpenAI/ChatGPT, IPs directas para Anthropic/Claude
const AI_DOMAINS = {
  // ChatGPT / OpenAI (Cloudflare CDN)
  'chatgpt.com':                 ['104.18.32.47',  '104.18.35.47'],
  'www.chatgpt.com':             ['104.18.32.47',  '104.18.35.47'],
  'ab.chatgpt.com':              ['104.18.32.47',  '104.18.35.47'],
  'chat.openai.com':             ['104.18.37.228', '104.18.32.228'],
  'api.openai.com':              ['162.159.140.245'],
  'auth.openai.com':             ['104.18.37.228', '104.18.32.228'],
  'platform.openai.com':         ['104.18.37.228', '104.18.32.228'],
  'cdn.oaistatic.com':           ['104.18.41.158', '104.18.40.158'],
  'files.oaiusercontent.com':    ['104.18.32.47',  '104.18.35.47'],
  'ws.chatgpt.com':              ['104.18.39.21',  '172.64.148.235'],
  // Claude / Anthropic (Cloudflare CDN)
  'claude.ai':                   ['160.79.104.10', '160.79.104.11'],
  'www.claude.ai':               ['160.79.104.10', '160.79.104.11'],
  'anthropic.com':               ['160.79.104.10', '160.79.104.11'],
  'api.anthropic.com':           ['160.79.104.10', '160.79.104.11'],
  'console.anthropic.com':       ['160.79.104.10', '160.79.104.11'],
  'cdn.anthropic.com':           ['160.79.104.10', '160.79.104.11'],
  'bridge.claudeusercontent.com':['160.79.104.10', '160.79.104.11'],
};

// Patrones para detectar dominios de AI
// - openai      -> chat.openai.com, api.openai.com, auth.openai.com, platform.openai.com
// - chatgpt     -> chatgpt.com, www.chatgpt.com, ab.chatgpt.com
// - anthropic   -> anthropic.com, api.anthropic.com, console.anthropic.com
// - claude      -> claude.ai, www.claude.ai
// - oaistatic   -> cdn.oaistatic.com  (CDN de assets de ChatGPT)
// - oaiusercontent -> files.oaiusercontent.com (subidas de archivos en ChatGPT)
const AI_PATTERNS = ['openai', 'chatgpt', 'anthropic', 'claude', 'oaistatic', 'oaiusercontent'];

// ══════════════════════════════════════════════════════════════
//  DNS over HTTPS — intenta Google primero, luego Cloudflare
// ══════════════════════════════════════════════════════════════
const DOH_SERVERS = [
  'https://dns.google/resolve',
  'https://cloudflare-dns.com/dns-query',
];

function dohResolveFrom(server, hostname) {
  return new Promise((resolve) => {
    const url = `${server}?name=${encodeURIComponent(hostname)}&type=A`;
    const req = https.get(url, { timeout: 4000, headers: { accept: 'application/dns-json' } }, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          if (json.Answer) {
            const ips = json.Answer.filter(a => a.type === 1).map(a => a.data);
            if (ips.length > 0) { resolve(ips); return; }
          }
          resolve(null);
        } catch (e) { resolve(null); }
      });
    });
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
  });
}

async function dohResolve(hostname) {
  for (const server of DOH_SERVERS) {
    const ips = await dohResolveFrom(server, hostname);
    if (ips && ips.length > 0) {
      AI_DOMAINS[hostname] = ips;
      console.log(`  [DoH] ${hostname} -> ${ips.join(', ')} (cached via ${server.includes('google') ? 'Google' : 'Cloudflare'})`);
      return ips[0];
    }
  }
  return null;
}

// ══════════════════════════════════════════════════════════════
//  Resolver: dominio -> IP
// ══════════════════════════════════════════════════════════════
async function resolveHost(hostname) {
  if (AI_DOMAINS[hostname] && AI_DOMAINS[hostname].length > 0) {
    return { ip: AI_DOMAINS[hostname][0], isAI: true };
  }
  const isAIDomain = AI_PATTERNS.some(p => hostname.toLowerCase().includes(p));
  if (isAIDomain) {
    const ip = await dohResolve(hostname);
    if (ip) return { ip, isAI: true };
    return { ip: hostname, isAI: true };
  }
  return { ip: hostname, isAI: false };
}

// ══════════════════════════════════════════════════════════════
//  Encontrar la posicion del SNI dentro del TLS ClientHello
// ══════════════════════════════════════════════════════════════
function findSNIOffset(data) {
  if (data.length < 5 || data[0] !== 0x16) return -1;
  if (data.length < 9 || data[5] !== 0x01) return -1;

  let offset = 9;
  offset += 2;   // ClientHello version
  offset += 32;  // Random
  if (offset >= data.length) return -1;

  const sessionIdLen = data[offset];
  offset += 1 + sessionIdLen;
  if (offset + 2 >= data.length) return -1;

  const cipherLen = (data[offset] << 8) | data[offset + 1];
  offset += 2 + cipherLen;
  if (offset + 1 >= data.length) return -1;

  const compLen = data[offset];
  offset += 1 + compLen;
  if (offset + 2 >= data.length) return -1;

  const extTotalLen = (data[offset] << 8) | data[offset + 1];
  offset += 2;
  const extEnd = offset + extTotalLen;

  while (offset + 4 < extEnd && offset + 4 < data.length) {
    const extType = (data[offset] << 8) | data[offset + 1];
    const extLen  = (data[offset + 2] << 8) | data[offset + 3];
    if (extType === 0x0000) {
      const nameStart = offset + 9;
      if (nameStart < data.length) {
        const nameLen = (data[offset + 7] << 8) | data[offset + 8];
        return nameStart + Math.floor(nameLen / 2);
      }
      return offset + 4;
    }
    offset += 4 + extLen;
  }
  return -1;
}

// ══════════════════════════════════════════════════════════════
//  TLS RECORD SPLITTING - Bypass DPI/SNI (3 fragmentos)
//
//  Estrategia:
//    Record 1: 1 solo byte del payload  (30ms delay)
//    Record 2: payload hasta antes del SNI  (50ms delay)
//    Record 3: SNI + resto del ClientHello
//
//  Con 3 registros y delays largos, el DPI no puede
//  reensamblar el ClientHello dentro de su ventana de analisis.
// ══════════════════════════════════════════════════════════════
function buildTlsRecord(version, slice) {
  const rec = Buffer.alloc(5 + slice.length);
  rec[0] = 0x16;
  rec[1] = (version >> 8) & 0xff;
  rec[2] = version & 0xff;
  rec[3] = (slice.length >> 8) & 0xff;
  rec[4] = slice.length & 0xff;
  slice.copy(rec, 5);
  return rec;
}

function delay(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function fragmentTLSClientHello(socket, data) {
  socket.setNoDelay(true);

  if (data.length < 10 || data[0] !== 0x16) {
    console.log(`    No TLS handshake, enviando directo (${data.length} bytes)`);
    socket.write(data);
    return;
  }

  const version   = (data[1] << 8) | data[2];
  const recordLen = (data[3] << 8) | data[4];
  const payload   = data.slice(5, 5 + recordLen);
  const trailing  = data.slice(5 + recordLen);

  // Punto de corte 2: 10 bytes antes del SNI
  const sniAbs = findSNIOffset(data);
  let cut2 = (sniAbs > 5 && sniAbs < 5 + recordLen)
    ? Math.max(2, sniAbs - 5 - 10)
    : Math.max(2, Math.floor(payload.length / 3));

  // Punto de corte 1: siempre el primer byte del payload
  const cut1 = 1;

  const part1 = payload.slice(0, cut1);        // 1 byte
  const part2 = payload.slice(cut1, cut2);      // hasta antes del SNI
  const part3 = payload.slice(cut2);            // SNI + resto

  const r1 = buildTlsRecord(version, part1);
  const r2 = buildTlsRecord(version, part2);
  const r3 = buildTlsRecord(version, part3);

  console.log(`    3-frag: R1=${r1.length}B | R2=${r2.length}B | R3=${r3.length}B  (SNI@${sniAbs}, cut1=${cut1}, cut2=${cut2})`);

  socket.write(r1);
  await delay(30);
  if (socket.destroyed) return;

  socket.write(r2);
  await delay(50);
  if (socket.destroyed) return;

  socket.write(r3);
  if (trailing.length > 0) socket.write(trailing);
  socket.setNoDelay(false);
}

// ══════════════════════════════════════════════════════════════
//  Servidor Proxy HTTP (requests planos)
// ══════════════════════════════════════════════════════════════
const server = http.createServer(async (req, res) => {
  try {
    const targetUrl = new URL(req.url);
    const hostname  = targetUrl.hostname;
    const port      = parseInt(targetUrl.port) || 80;
    const { ip, isAI } = await resolveHost(hostname);

    const tag = isAI ? '\x1b[36m[AI]\x1b[0m   ' : '\x1b[90m[PASS]\x1b[0m';
    console.log(`${tag} HTTP ${req.method} ${hostname} -> ${ip}:${port}`);

    const options = {
      hostname: ip,
      port,
      path: targetUrl.pathname + targetUrl.search,
      method: req.method,
      headers: { ...req.headers },
      timeout: 15000,
    };
    options.headers.host = hostname + (targetUrl.port ? ':' + targetUrl.port : '');

    const proxyReq = http.request(options, (proxyRes) => {
      res.writeHead(proxyRes.statusCode, proxyRes.headers);
      proxyRes.pipe(res);
    });
    req.pipe(proxyReq);
    proxyReq.on('error', (err) => {
      console.error(`  \x1b[31m[ERROR]\x1b[0m HTTP ${hostname}: ${err.message}`);
      if (!res.headersSent) { res.writeHead(502); res.end('Bad Gateway'); }
    });
    proxyReq.on('timeout', () => {
      proxyReq.destroy();
      if (!res.headersSent) { res.writeHead(504); res.end('Gateway Timeout'); }
    });
  } catch (err) {
    console.error(`  \x1b[31m[ERROR]\x1b[0m HTTP parse: ${err.message}`);
    if (!res.headersSent) { res.writeHead(400); res.end('Bad Request'); }
  }
});

// ══════════════════════════════════════════════════════════════
//  CONNECT handler (tunel HTTPS con fragmentacion SNI)
// ══════════════════════════════════════════════════════════════
server.on('connect', async (req, clientSocket, head) => {
  const [hostname, portStr] = req.url.split(':');
  const port = parseInt(portStr) || 443;
  const { ip, isAI } = await resolveHost(hostname);

  const tag = isAI ? '\x1b[36m[AI]\x1b[0m   ' : '\x1b[90m[PASS]\x1b[0m';
  console.log(`${tag} CONNECT ${hostname}:${port} -> ${ip}:${port}`);

  const serverSocket = net.connect({ host: ip, port, timeout: 30000 }, () => {
    serverSocket.setNoDelay(true);
    clientSocket.setNoDelay(true);

    clientSocket.write(
      'HTTP/1.1 200 Connection Established\r\n' +
      'Proxy-Agent: AIProxy\r\n' +
      '\r\n'
    );

    if (head.length > 0) serverSocket.write(head);

    let firstPacket = true;

    clientSocket.on('data', (chunk) => {
      if (serverSocket.destroyed) return;
      if (firstPacket && isAI && SPLIT_ENABLED) {
        firstPacket = false;
        console.log(`  \x1b[33m[FRAG]\x1b[0m TLS ClientHello ${hostname} (${chunk.length} bytes)`);
        fragmentTLSClientHello(serverSocket, chunk).catch(() => {});
      } else {
        serverSocket.write(chunk);
      }
    });

    serverSocket.on('data', (chunk) => {
      if (clientSocket.destroyed) return;
      clientSocket.write(chunk);
    });
  });

  serverSocket.on('error', (err) => {
    console.error(`  \x1b[31m[ERROR]\x1b[0m ${hostname}: ${err.message}`);
    clientSocket.end();
  });
  clientSocket.on('error', () => { serverSocket.destroy(); });
  serverSocket.on('end', () => { clientSocket.end(); });
  clientSocket.on('end', () => { serverSocket.end(); });
  serverSocket.on('timeout', () => {
    console.error(`  \x1b[31m[TIMEOUT]\x1b[0m ${hostname}`);
    serverSocket.destroy();
    clientSocket.end();
  });
});

// ══════════════════════════════════════════════════════════════
//  Arranque
// ══════════════════════════════════════════════════════════════
server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`\n\x1b[31mERROR: Puerto ${PROXY_PORT} en uso. Cierra otra instancia primero.\x1b[0m\n`);
    process.exit(1);
  }
  console.error(`[SERVER ERROR] ${err.message}`);
});

server.listen(PROXY_PORT, '127.0.0.1', () => {
  console.log('');
  console.log('\x1b[36m╔════════════════════════════════════════════════════════╗\x1b[0m');
  console.log('\x1b[36m║        AI PROXY v1.0 - ChatGPT + Claude Bypass        ║\x1b[0m');
  console.log('\x1b[36m╚════════════════════════════════════════════════════════╝\x1b[0m');
  console.log('');
  console.log(`\x1b[32mProxy: http://127.0.0.1:${PROXY_PORT}\x1b[0m`);
  console.log(`\x1b[32mFragmentacion SNI: ${SPLIT_ENABLED ? 'ACTIVADA' : 'DESACTIVADA (modo diagnostico)'}\x1b[0m`);
  console.log('\x1b[32mDNS-over-HTTPS:    ACTIVADO (dns.google)\x1b[0m');
  console.log('');
  console.log('\x1b[33mDominios mapeados (IP hardcodeada):\x1b[0m');
  console.log('─'.repeat(65));
  for (const [domain, ips] of Object.entries(AI_DOMAINS)) {
    console.log(`  ${domain.padEnd(42)} -> ${ips[0]}`);
  }
  console.log('─'.repeat(65));
  console.log('');
  console.log('Subdominios desconocidos se resuelven via DoH (Google + Cloudflare).');
  console.log('Esperando conexiones...');
  console.log('');
});

process.on('SIGINT', () => {
  console.log('\n\x1b[33mCerrando AI proxy...\x1b[0m');
  server.close(() => process.exit(0));
});
