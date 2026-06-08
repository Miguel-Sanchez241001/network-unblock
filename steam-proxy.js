const http = require('http');
const https = require('https');
const net = require('net');

// ══════════════════════════════════════════════════════════════
//  STEAM PROXY v2.0 - Bypass DNS + SNI blocking
//  1. Resuelve dominios Steam a IPs directas (via Google DNS)
//  2. Fragmenta TLS ClientHello para evadir DPI/SNI inspection
//     usando setNoDelay(true) para forzar paquetes TCP separados
// ══════════════════════════════════════════════════════════════

const PROXY_PORT = 8888;

// --- IPs resueltas via dns.google (region Sudamerica) ---
const STEAM_DOMAINS = {
  'store.steampowered.com':                ['23.57.121.205'],
  'api.steampowered.com':                  ['96.6.206.56'],
  'steamcommunity.com':                    ['96.6.206.56'],
  'www.steamcommunity.com':                ['96.6.206.56'],
  'login.steampowered.com':                ['96.6.206.56'],
  'help.steampowered.com':                 ['96.6.206.56'],
  'steam.tv':                              ['23.196.72.39'],
  'checkout.steampowered.com':             ['96.6.206.56'],
  'cm0.steampowered.com':                  ['162.254.195.44', '162.254.193.6'],
  'cdn.cloudflare.steamstatic.com':        ['200.60.190.10', '200.60.190.8'],
  'cdn.akamai.steamstatic.com':            ['200.60.190.10', '200.60.190.8'],
  'community.cloudflare.steamstatic.com':  ['200.60.190.10', '200.60.190.19'],
  'store.cloudflare.steamstatic.com':      ['200.60.190.24', '200.60.190.27'],
  'shared.cloudflare.steamstatic.com':     ['200.60.190.26', '200.60.190.27'],
  'client-update.akamai.steamstatic.com':  ['200.60.190.224', '200.60.190.123'],
  'avatars.steamstatic.com':               ['199.232.211.52', '199.232.215.52'],
  'steambroadcast.akamaized.net':          ['200.60.190.19', '200.60.190.24'],
  'steamcdn-a.akamaihd.net':              ['200.60.190.10'],
  'lancache.steamcontent.com':             ['155.133.255.9', '155.133.255.8'],
};

const STEAM_PATTERNS = ['steam', 'valve'];

// ══════════════════════════════════════════════════════════════
//  DNS over HTTPS (Google DNS) - para dominios Steam no mapeados
// ══════════════════════════════════════════════════════════════
function dohResolve(hostname) {
  return new Promise((resolve) => {
    const url = `https://dns.google/resolve?name=${encodeURIComponent(hostname)}&type=A`;
    const req = https.get(url, { timeout: 5000 }, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          if (json.Answer) {
            const ips = json.Answer.filter(a => a.type === 1).map(a => a.data);
            if (ips.length > 0) {
              STEAM_DOMAINS[hostname] = ips;
              console.log(`  [DoH] ${hostname} -> ${ips.join(', ')} (cached)`);
              resolve(ips[0]);
              return;
            }
          }
          resolve(null);
        } catch (e) { resolve(null); }
      });
    });
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
  });
}

// ══════════════════════════════════════════════════════════════
//  Resolver: dominio -> IP
// ══════════════════════════════════════════════════════════════
async function resolveHost(hostname) {
  if (STEAM_DOMAINS[hostname]) {
    return { ip: STEAM_DOMAINS[hostname][0], isSteam: true };
  }
  const isSteamDomain = STEAM_PATTERNS.some(p => hostname.toLowerCase().includes(p));
  if (isSteamDomain) {
    const ip = await dohResolve(hostname);
    if (ip) return { ip, isSteam: true };
    return { ip: hostname, isSteam: true };
  }
  return { ip: hostname, isSteam: false };
}

// ══════════════════════════════════════════════════════════════
//  Encontrar la posicion del SNI dentro del TLS ClientHello
//  para cortar exactamente ahi
// ══════════════════════════════════════════════════════════════
function findSNIOffset(data) {
  // TLS Record: byte 0=ContentType(22=Handshake), 1-2=Version, 3-4=Length
  if (data.length < 5 || data[0] !== 0x16) return -1;

  // Handshake header: byte 5=HandshakeType(1=ClientHello), 6-8=Length
  if (data.length < 9 || data[5] !== 0x01) return -1;

  let offset = 9; // skip handshake header

  // ClientHello: 2 bytes version
  offset += 2;
  // 32 bytes random
  offset += 32;
  if (offset >= data.length) return -1;

  // Session ID (1 byte length + data)
  const sessionIdLen = data[offset];
  offset += 1 + sessionIdLen;
  if (offset + 2 >= data.length) return -1;

  // Cipher Suites (2 bytes length + data)
  const cipherLen = (data[offset] << 8) | data[offset + 1];
  offset += 2 + cipherLen;
  if (offset + 1 >= data.length) return -1;

  // Compression Methods (1 byte length + data)
  const compLen = data[offset];
  offset += 1 + compLen;
  if (offset + 2 >= data.length) return -1;

  // Extensions (2 bytes total length)
  const extTotalLen = (data[offset] << 8) | data[offset + 1];
  offset += 2;
  const extEnd = offset + extTotalLen;

  // Buscar extension SNI (type 0x0000)
  while (offset + 4 < extEnd && offset + 4 < data.length) {
    const extType = (data[offset] << 8) | data[offset + 1];
    const extLen = (data[offset + 2] << 8) | data[offset + 3];
    if (extType === 0x0000) {
      // SNI extension encontrada! Cortar en el medio del nombre
      // offset+4 = SNI list length (2 bytes)
      // offset+6 = SNI type (1 byte)
      // offset+7 = SNI name length (2 bytes)
      // offset+9 = inicio del nombre del servidor
      const nameStart = offset + 9;
      if (nameStart < data.length) {
        const nameLen = (data[offset + 7] << 8) | data[offset + 8];
        // Cortar a la mitad del nombre del servidor
        return nameStart + Math.floor(nameLen / 2);
      }
      return offset + 4; // fallback: cortar al inicio del SNI data
    }
    offset += 4 + extLen;
  }

  return -1; // SNI no encontrado
}

// ══════════════════════════════════════════════════════════════
//  TLS RECORD SPLITTING - Bypass DPI avanzado
//
//  En vez de solo fragmentar TCP (que el DPI puede reensamblar),
//  dividimos el ClientHello en DOS REGISTROS TLS validos.
//  El servidor los reensamblara (RFC 8446 Sec 5.1), pero el DPI
//  no puede porque necesitaria parsear TLS record protocol.
//
//  Original:  [TLS Record: type=0x16, ver, len=N] [ClientHello N bytes]
//  Split:     [TLS Record 1: type=0x16, ver, len=X] [primeros X bytes]
//             [TLS Record 2: type=0x16, ver, len=Y] [siguientes Y bytes]
//  donde X+Y = N, y el SNI queda en Record 2
//
//  Ademas enviamos cada record como TCP segment separado (setNoDelay)
// ══════════════════════════════════════════════════════════════
function fragmentTLSClientHello(socket, data) {
  socket.setNoDelay(true);

  // Verificar que es un TLS Handshake record
  if (data.length < 10 || data[0] !== 0x16) {
    console.log(`    No TLS handshake, enviando directo (${data.length} bytes)`);
    socket.write(data);
    return Promise.resolve();
  }

  const version = (data[1] << 8) | data[2];  // TLS version
  const recordLen = (data[3] << 8) | data[4]; // payload length
  const payload = data.slice(5, 5 + recordLen);
  const trailing = data.slice(5 + recordLen);  // datos extra despues del record

  // Encontrar SNI offset DENTRO del payload (no del buffer completo)
  // El findSNIOffset trabaja sobre el buffer completo (con header TLS)
  // asi que el offset relativo al payload es: sniOffset - 5
  const sniOffsetAbsolute = findSNIOffset(data);
  let splitPoint;

  if (sniOffsetAbsolute > 5 && sniOffsetAbsolute < 5 + recordLen) {
    // Cortar justo ANTES del inicio de la extension SNI
    // (retroceder al inicio de la extension, no al medio del nombre)
    splitPoint = sniOffsetAbsolute - 5 - 10; // unos bytes antes del SNI
    if (splitPoint < 1) splitPoint = 1;
    console.log(`    TLS Record Split: SNI@${sniOffsetAbsolute}, split payload@${splitPoint}`);
  } else {
    // Fallback: cortar temprano
    splitPoint = Math.min(50, Math.floor(payload.length / 4));
    console.log(`    TLS Record Split: generic@${splitPoint}`);
  }

  const part1 = payload.slice(0, splitPoint);
  const part2 = payload.slice(splitPoint);

  // Construir TLS Record 1
  const record1 = Buffer.alloc(5 + part1.length);
  record1[0] = 0x16; // Handshake
  record1[1] = (version >> 8) & 0xff;
  record1[2] = version & 0xff;
  record1[3] = (part1.length >> 8) & 0xff;
  record1[4] = part1.length & 0xff;
  part1.copy(record1, 5);

  // Construir TLS Record 2
  const record2 = Buffer.alloc(5 + part2.length);
  record2[0] = 0x16; // Handshake
  record2[1] = (version >> 8) & 0xff;
  record2[2] = version & 0xff;
  record2[3] = (part2.length >> 8) & 0xff;
  record2[4] = part2.length & 0xff;
  part2.copy(record2, 5);

  console.log(`    -> Record1: ${record1.length}B (header+${part1.length}) | Record2: ${record2.length}B (header+${part2.length})`);

  // Enviar como TCP segments separados con delays
  socket.write(record1);
  return new Promise((resolve) => {
    setTimeout(() => {
      if (socket.destroyed) { resolve(); return; }
      socket.write(record2);
      if (trailing.length > 0) {
        socket.write(trailing);
      }
      socket.setNoDelay(false);
      resolve();
    }, 5);
  });
}

// ══════════════════════════════════════════════════════════════
//  Servidor Proxy HTTP
// ══════════════════════════════════════════════════════════════
const server = http.createServer(async (req, res) => {
  try {
    const targetUrl = new URL(req.url);
    const hostname = targetUrl.hostname;
    const port = parseInt(targetUrl.port) || 80;
    const { ip, isSteam } = await resolveHost(hostname);

    const tag = isSteam ? '\x1b[36m[STEAM]\x1b[0m' : '\x1b[90m[PASS]\x1b[0m';
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
  const { ip, isSteam } = await resolveHost(hostname);

  const tag = isSteam ? '\x1b[36m[STEAM]\x1b[0m' : '\x1b[90m[PASS]\x1b[0m';
  console.log(`${tag} CONNECT ${hostname}:${port} -> ${ip}:${port}`);

  const serverSocket = net.connect({ host: ip, port, timeout: 30000 }, () => {
    // Desactivar Nagle en ambos sockets
    serverSocket.setNoDelay(true);
    clientSocket.setNoDelay(true);

    clientSocket.write(
      'HTTP/1.1 200 Connection Established\r\n' +
      'Proxy-Agent: SteamProxy\r\n' +
      '\r\n'
    );

    if (head.length > 0) {
      serverSocket.write(head);
    }

    let firstPacket = true;

    clientSocket.on('data', (chunk) => {
      if (serverSocket.destroyed) return;
      if (firstPacket && isSteam) {
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
    console.error(`\n\x1b[31mERROR: Puerto ${PROXY_PORT} en uso.\x1b[0m\n`);
    process.exit(1);
  }
  console.error(`[SERVER ERROR] ${err.message}`);
});

server.listen(PROXY_PORT, '127.0.0.1', () => {
  console.log('');
  console.log('\x1b[36m╔════════════════════════════════════════════════════════╗\x1b[0m');
  console.log('\x1b[36m║       STEAM PROXY v2.0 - DNS + SNI Bypass             ║\x1b[0m');
  console.log('\x1b[36m╚════════════════════════════════════════════════════════╝\x1b[0m');
  console.log('');
  console.log(`\x1b[32mProxy: http://127.0.0.1:${PROXY_PORT}\x1b[0m`);
  console.log('\x1b[32mFragmentacion SNI: ACTIVADA (setNoDelay + split en SNI)\x1b[0m');
  console.log('');
  console.log('\x1b[33mDominios mapeados:\x1b[0m');
  console.log('─'.repeat(65));
  for (const [domain, ips] of Object.entries(STEAM_DOMAINS)) {
    console.log(`  ${domain.padEnd(45)} -> ${ips.join(', ')}`);
  }
  console.log('─'.repeat(65));
  console.log('');
  console.log('Esperando conexiones...');
  console.log('');
});

process.on('SIGINT', () => {
  console.log('\n\x1b[33mCerrando proxy...\x1b[0m');
  server.close(() => process.exit(0));
});
