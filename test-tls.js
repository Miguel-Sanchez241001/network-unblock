const tls  = require('tls');
const net  = require('net');
const https = require('https');

// IPs a probar (todas las encontradas en test-ips.js)
const PRUEBAS = [
  // ChatGPT / OpenAI
  { dominio: 'chatgpt.com',         ip: '104.18.32.47'    },
  { dominio: 'chatgpt.com',         ip: '172.64.155.209'  },
  { dominio: 'chat.openai.com',     ip: '172.64.150.28'   },
  { dominio: 'chat.openai.com',     ip: '104.18.37.228'   },
  { dominio: 'api.openai.com',      ip: '162.159.140.245' },
  { dominio: 'api.openai.com',      ip: '172.66.0.243'    },
  // Claude / Anthropic
  { dominio: 'claude.ai',           ip: '160.79.104.10'   },
  { dominio: 'claude.ai',           ip: '160.79.104.11'   },
  { dominio: 'api.anthropic.com',   ip: '160.79.104.10'   },
];

const GREEN = '\x1b[32m';
const RED   = '\x1b[31m';
const CYAN  = '\x1b[36m';
const GRAY  = '\x1b[90m';
const RESET = '\x1b[0m';

// Test TLS con SNI normal
// rejectUnauthorized: false es intencional — solo queremos saber si el firewall
// deja pasar el handshake. No se transmiten datos ni credenciales.
function testTlsConSni(ip, dominio, puerto = 443) {
  return new Promise((resolve) => {
    const sock = tls.connect({
      host: ip, port: puerto,
      servername: dominio,
      rejectUnauthorized: false, // solo diagnostico, nunca en produccion
      timeout: 8000,
    });
    const timer = setTimeout(() => { sock.destroy(); resolve('TIMEOUT'); }, 8000);
    sock.on('secureConnect', () => { clearTimeout(timer); sock.destroy(); resolve('OK'); });
    sock.on('error', (e) => { clearTimeout(timer); resolve(e.code || 'ERROR'); });
    sock.on('timeout', () => { clearTimeout(timer); sock.destroy(); resolve('TIMEOUT'); });
  });
}

// Test TLS sin SNI — si esto pasa pero "con SNI" falla, el bloqueo es puramente SNI
function testTlsSinSni(ip, puerto = 443) {
  return new Promise((resolve) => {
    const sock = tls.connect({
      host: ip, port: puerto,
      servername: '',
      rejectUnauthorized: false, // solo diagnostico, nunca en produccion
      timeout: 8000,
    });
    const timer = setTimeout(() => { sock.destroy(); resolve('TIMEOUT'); }, 8000);
    sock.on('secureConnect', () => { clearTimeout(timer); sock.destroy(); resolve('OK'); });
    sock.on('error', (e) => { clearTimeout(timer); resolve(e.code || 'ERROR'); });
    sock.on('timeout', () => { clearTimeout(timer); sock.destroy(); resolve('TIMEOUT'); });
  });
}

function statusColor(s) {
  if (s === 'OK')      return `${GREEN}OK          ${RESET}`;
  if (s === 'TIMEOUT') return `${GRAY}TIMEOUT     ${RESET}`;
  return `${RED}${s.padEnd(12)}${RESET}`;
}

(async () => {
  console.log('');
  console.log(`${CYAN}Test TLS — con SNI vs sin SNI${RESET}`);
  console.log(`${GRAY}Si "sin SNI" pasa pero "con SNI" falla -> bloqueo es 100% por SNI${RESET}`);
  console.log('─'.repeat(75));
  console.log(`  ${'IP'.padEnd(18)} ${'Dominio'.padEnd(25)} ${'con SNI'.padEnd(14)} sin SNI`);
  console.log('─'.repeat(75));

  const libresConSni  = [];
  const libresSinSni  = [];

  for (const { dominio, ip } of PRUEBAS) {
    const [conSni, sinSni] = await Promise.all([
      testTlsConSni(ip, dominio),
      testTlsSinSni(ip),
    ]);

    const tag = conSni === 'OK' ? `${GREEN}[PASA]${RESET}` : (sinSni === 'OK' ? `${CYAN}[SNI]${RESET} ` : `${RED}[IP] ${RESET} `);
    console.log(`  ${ip.padEnd(18)} ${dominio.padEnd(25)} ${statusColor(conSni)} ${statusColor(sinSni)}`);

    if (conSni === 'OK')  libresConSni.push({ dominio, ip });
    if (sinSni === 'OK')  libresSinSni.push({ dominio, ip });
  }

  console.log('─'.repeat(75));
  console.log('');

  if (libresConSni.length > 0) {
    console.log(`${GREEN}IPs donde TLS con SNI pasa completo (conectividad real):${RESET}`);
    libresConSni.forEach(({ dominio, ip }) =>
      console.log(`  ${GREEN}LIBRE${RESET}  ${dominio} -> ${ip}`)
    );
  } else if (libresSinSni.length > 0) {
    console.log(`${CYAN}Ninguna IP pasa TLS con SNI, pero si pasa sin SNI.${RESET}`);
    console.log(`${CYAN}El firewall bloquea por SNI — necesitamos mejor tecnica de ocultacion.${RESET}`);
    libresSinSni.forEach(({ ip }) =>
      console.log(`  ${CYAN}SIN SNI OK${RESET}  ${ip}`)
    );
  } else {
    console.log(`${RED}Todas las IPs bloqueadas en TLS (con y sin SNI).${RESET}`);
    console.log(`${RED}El firewall bloquea por IP o hace inspeccion SSL profunda.${RESET}`);
  }

  console.log('');
})();
