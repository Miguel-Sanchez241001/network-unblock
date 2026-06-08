const https = require('https');
const net   = require('net');

// Dominios a probar
const DOMINIOS = [
  'chatgpt.com',
  'chat.openai.com',
  'api.openai.com',
  'ws.chatgpt.com',
  'cdn.oaistatic.com',
  'claude.ai',
  'api.anthropic.com',
  'console.anthropic.com',
];

// Varios servidores DoH para obtener distintas IPs por region
const DOH_SERVERS = [
  { name: 'Google',     url: 'https://dns.google/resolve' },
  { name: 'Cloudflare', url: 'https://cloudflare-dns.com/dns-query' },
  { name: 'Quad9',      url: 'https://dns.quad9.net/dns-query' },
  { name: 'AdGuard',    url: 'https://dns.adguard-dns.com/resolve' },
];

function dohQuery(server, hostname) {
  return new Promise((resolve) => {
    const url = `${server.url}?name=${encodeURIComponent(hostname)}&type=A`;
    const req = https.get(url, {
      timeout: 5000,
      headers: { accept: 'application/dns-json' }
    }, (res) => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        try {
          const json = JSON.parse(data);
          const ips = (json.Answer || [])
            .filter(a => a.type === 1)
            .map(a => a.data);
          resolve(ips);
        } catch { resolve([]); }
      });
    });
    req.on('error', () => resolve([]));
    req.on('timeout', () => { req.destroy(); resolve([]); });
  });
}

// Prueba si se puede abrir conexion TCP al puerto 443
function testTcp(ip, port = 443, timeoutMs = 4000) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    let done = false;
    const finish = (result) => {
      if (done) return;
      done = true;
      sock.destroy();
      resolve(result);
    };
    sock.setTimeout(timeoutMs);
    sock.connect(port, ip, () => finish('OK'));
    sock.on('error', (e) => finish(e.code || 'ERROR'));
    sock.on('timeout', () => finish('TIMEOUT'));
  });
}

const CYAN  = '\x1b[36m';
const GREEN = '\x1b[32m';
const RED   = '\x1b[31m';
const GRAY  = '\x1b[90m';
const RESET = '\x1b[0m';

(async () => {
  console.log('');
  console.log(`${CYAN}Buscando IPs alternativas para dominios AI...${RESET}`);
  console.log('─'.repeat(70));

  // Resultados finales: dominio -> Set de IPs unicas
  const porDominio = {};

  // 1. Recolectar IPs de todos los servidores DoH
  for (const dominio of DOMINIOS) {
    const ipsSet = new Set();
    for (const server of DOH_SERVERS) {
      const ips = await dohQuery(server, dominio);
      ips.forEach(ip => ipsSet.add(ip));
    }
    porDominio[dominio] = [...ipsSet];
  }

  // 2. Probar conectividad TCP para cada IP unica
  const allIps = new Set(Object.values(porDominio).flat());
  console.log(`\nProbando conectividad TCP:443 para ${allIps.size} IPs unicas...\n`);

  const resultados = {};
  for (const ip of allIps) {
    const estado = await testTcp(ip);
    resultados[ip] = estado;
    const color = estado === 'OK' ? GREEN : estado === 'TIMEOUT' ? GRAY : RED;
    console.log(`  ${color}${estado.padEnd(12)}${RESET} ${ip}`);
  }

  // 3. Resumen por dominio
  console.log('');
  console.log('─'.repeat(70));
  console.log(`${CYAN}RESUMEN - IPs alcanzables por dominio:${RESET}`);
  console.log('─'.repeat(70));

  const recomendaciones = {};

  for (const dominio of DOMINIOS) {
    const ips = porDominio[dominio];
    const alcanzables = ips.filter(ip => resultados[ip] === 'OK');
    const bloqueadas  = ips.filter(ip => resultados[ip] !== 'OK');

    console.log(`\n  ${CYAN}${dominio}${RESET}`);
    if (alcanzables.length > 0) {
      alcanzables.forEach(ip => console.log(`    ${GREEN}LIBRE    ${RESET} ${ip}`));
      recomendaciones[dominio] = alcanzables;
    } else {
      console.log(`    ${RED}Sin IPs libres encontradas${RESET}`);
    }
    if (bloqueadas.length > 0) {
      bloqueadas.forEach(ip => console.log(`    ${RED}BLOQUEADA${RESET} ${ip} (${resultados[ip]})`));
    }
  }

  // 4. Generar el bloque AI_DOMAINS listo para pegar en ai-proxy.js
  const libres = Object.entries(recomendaciones);
  if (libres.length > 0) {
    console.log('');
    console.log('─'.repeat(70));
    console.log(`${GREEN}Bloque AI_DOMAINS para pegar en ai-proxy.js:${RESET}`);
    console.log('─'.repeat(70));
    console.log('const AI_DOMAINS = {');
    for (const [dominio, ips] of libres) {
      const lista = ips.slice(0, 3).map(ip => `'${ip}'`).join(', ');
      console.log(`  '${dominio}':${' '.repeat(Math.max(1, 38 - dominio.length))}[${lista}],`);
    }
    console.log('};');
  } else {
    console.log('');
    console.log(`${RED}Todas las IPs encontradas estan bloqueadas a nivel TCP.${RESET}`);
    console.log('El firewall hace bloqueo por IP ademas de SNI.');
  }

  console.log('');
})();
