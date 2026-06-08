# Conceptos técnicos explicados desde cero

Este documento explica cada concepto que se usó en el proyecto, desde los fundamentos hasta cómo se aplicó en el código. Está escrito para alguien que sabe programar pero no necesariamente conoce redes o criptografía.

---

## 1. Cómo funciona HTTPS (la base de todo)

Cuando escribís `https://chatgpt.com` en el navegador, pasan muchas cosas antes de que veas la página. Hay que entenderlas para comprender qué bloqueaban y cómo lo evadimos.

### 1.1 DNS — la guía telefónica de internet

Los dominios como `chatgpt.com` son nombres legibles para humanos. Las computadoras se hablan por **IPs** (números como `104.18.32.47`). Para traducir uno al otro existe el **DNS (Domain Name System)**.

```
Tu computadora                Router/ISP               Servidor DNS
     │                             │                        │
     │──── "¿Cuál es la IP       ──►│                        │
     │      de chatgpt.com?"        │──── pregunta ─────────►│
     │                             │◄─── respuesta: 104.x.x ─│
     │◄─── "104.18.32.47" ─────────│                        │
     │                             │                        │
```

**El problema:** el router corporativo puede interceptar esa consulta DNS y responder con una IP falsa o no responder, impidiendo que llegues al sitio real. Esto se llama **DNS Hijacking**.

```
Tu computadora                Router (malicioso)
     │                             │
     │──── "¿IP de chatgpt.com?" ──►│
     │◄─── "0.0.0.0" (IP falsa) ───│   ← chatgpt.com bloqueado
```

### 1.2 TCP — el canal de comunicación

Una vez que tenés la IP, tu computadora abre una **conexión TCP** al servidor. TCP garantiza que los datos lleguen en orden y sin errores. La conexión se establece con un "handshake" de 3 pasos:

```
Tu PC ──── SYN ──────────────► Servidor
Tu PC ◄─── SYN-ACK ───────────  Servidor   ← "OK, te escucho"
Tu PC ──── ACK ──────────────► Servidor    ← "Perfecto, empecemos"
```

Recién después de esto empieza la comunicación real.

### 1.3 TLS — el cifrado de HTTPS

Pero antes de enviar tu contraseña o datos privados, el navegador negocia **cifrado** con el servidor. Esto se llama **TLS (Transport Layer Security)** y es lo que hace que la URL diga `https://` en vez de `http://`.

El proceso de negociación se llama **TLS Handshake** y empieza con el mensaje más importante para este proyecto:

```
Tu PC ──── TLS ClientHello ──► Servidor
```

---

## 2. El TLS ClientHello — el mensaje clave

El **ClientHello** es el primer mensaje que el navegador manda al servidor para iniciar TLS. Contiene:

- La versión de TLS que soporta el cliente (1.2, 1.3)
- Los algoritmos de cifrado que puede usar (**cipher suites**)
- Datos aleatorios para generar las claves
- **Extensiones**, entre ellas la más importante para nosotros:

### 2.1 SNI — Server Name Indication

El SNI es una extensión del ClientHello que dice **a qué dominio nos queremos conectar**, en texto claro, antes de que empiece el cifrado.

¿Por qué existe? Porque muchos servidores comparten una misma IP (Cloudflare, por ejemplo, sirve millones de sitios desde el mismo IP). El servidor necesita saber a cuál de todos los sitios que aloja querés acceder, para presentarte el certificado correcto. No puede saber esto después de que empiece el cifrado porque el certificado va primero.

**El problema:** el SNI viaja en texto claro, antes del cifrado. Cualquiera que observe el tráfico puede leerlo.

```
[TLS Record]
  ContentType: 0x16  (Handshake)
  Version: 0x0303    (TLS 1.2)
  Length: 0x06F9     (1785 bytes)
  [Handshake]
    HandshakeType: 0x01  (ClientHello)
    [ClientHello]
      Version: TLS 1.3
      Random: 32 bytes aleatorios
      SessionID: ...
      CipherSuites: TLS_AES_128_GCM_SHA256, ...
      [Extensions]
        [SNI extension - type 0x0000]
          server_name: "chatgpt.com"   ← TEXTO CLARO, LEGIBLE POR EL DPI
```

### 2.2 Estructura binaria del ClientHello

El ClientHello tiene una estructura binaria precisa. Estos son los offsets relevantes:

```
Byte 0:     0x16          ← ContentType: Handshake
Bytes 1-2:  0x03 0x01     ← TLS Version (1.0 en el record header)
Bytes 3-4:  longitud del record
Byte 5:     0x01          ← HandshakeType: ClientHello
Bytes 6-8:  longitud del handshake
Bytes 9-10: versión TLS del ClientHello
Bytes 11-42: Random (32 bytes)
Byte 43:    longitud del Session ID
...         Session ID
...         longitud de CipherSuites (2 bytes)
...         CipherSuites
...         longitud de Compression Methods (1 byte)
...         Compression Methods
...         longitud total de Extensions (2 bytes)
...         Extensions
              Extension type 0x0000 = SNI  ← acá está lo que buscamos
              Extension length
              SNI list length
              Name type: 0x00 (host_name)
              Name length
              Server name: "chatgpt.com"  ← texto en claro
```

**Aplicación en el código — `findSNIOffset()`:**

```javascript
function findSNIOffset(data) {
  if (data.length < 5 || data[0] !== 0x16) return -1;  // verificar que es TLS Handshake
  if (data.length < 9 || data[5] !== 0x01) return -1;  // verificar que es ClientHello

  let offset = 9;
  offset += 2;   // saltear ClientHello version
  offset += 32;  // saltear Random (32 bytes fijos)

  // Session ID: 1 byte de longitud + los datos
  const sessionIdLen = data[offset];
  offset += 1 + sessionIdLen;

  // Cipher Suites: 2 bytes de longitud + los datos
  const cipherLen = (data[offset] << 8) | data[offset + 1];
  offset += 2 + cipherLen;

  // Compression Methods: 1 byte de longitud + los datos
  const compLen = data[offset];
  offset += 1 + compLen;

  // Extensions: iterar hasta encontrar type 0x0000 (SNI)
  const extTotalLen = (data[offset] << 8) | data[offset + 1];
  offset += 2;
  const extEnd = offset + extTotalLen;

  while (offset + 4 < extEnd) {
    const extType = (data[offset] << 8) | data[offset + 1];
    const extLen  = (data[offset + 2] << 8) | data[offset + 3];

    if (extType === 0x0000) {                    // ← encontramos SNI
      const nameStart = offset + 9;              // offset del texto del nombre
      const nameLen = (data[offset + 7] << 8) | data[offset + 8];
      return nameStart + Math.floor(nameLen / 2); // ← retorna el punto medio del nombre
    }
    offset += 4 + extLen;
  }
  return -1;
}
```

---

## 3. DPI — Deep Packet Inspection

El **DPI (Deep Packet Inspection)** es la tecnología que usan los firewalls modernos (como PaloAlto NGFW) para inspeccionar el contenido de los paquetes de red más allá de la IP y el puerto.

En vez de ver solo "hay un paquete de 192.168.1.5 al puerto 443", el DPI lee dentro del paquete y puede decir "este paquete es TLS, el SNI dice `chatgpt.com`, está en la categoría AI Tools, bloqueado".

### 3.1 Cómo opera el DPI en una conexión HTTPS

```
Tu PC ──── TCP SYN ──────────────────────────────────► Internet
Tu PC ◄─── TCP SYN-ACK ──────────────────────────────  Internet

Tu PC ──── TLS ClientHello ──► [DPI lee el SNI] ──────► Internet
                                    │
                               "chatgpt.com"
                               Categoría: AI Tools
                               Política: BLOQUEAR
                                    │
Tu PC ◄─── TCP RST ─────────────────┘
(ECONNRESET)
```

### 3.2 Por qué el DPI puede leer el SNI

Aunque HTTPS cifra el contenido de la comunicación, el SNI va en el primer mensaje del handshake TLS (el ClientHello), que viaja **antes** de que se establezca el cifrado. Es necesariamente así porque el servidor necesita saber qué certificado presentar.

El DPI simplemente tiene que:
1. Ver que el paquete va al puerto 443
2. Verificar que el primer byte es `0x16` (TLS Handshake)
3. Parsear la estructura binaria hasta encontrar la extensión SNI (type `0x0000`)
4. Leer el texto del nombre del servidor

---

## 4. TLS Record Protocol — la clave del bypass

### 4.1 ¿Qué es un TLS Record?

El protocolo TLS opera sobre un concepto llamado **TLS Record**. Todo mensaje TLS (handshake, datos de aplicación, alertas) se envía dentro de uno o más registros TLS. Un registro tiene:

```
[5 bytes de header]  [payload de longitud variable]
  │  │  │  │  │
  │  └──┘  └──┘
  │  versión  longitud del payload (2 bytes, max 16KB)
  │
  ContentType:
    0x14 = ChangeCipherSpec
    0x15 = Alert
    0x16 = Handshake  ← el que nos importa
    0x17 = ApplicationData
```

### 4.2 La regla clave del RFC 8446

El RFC 8446 (estándar de TLS 1.3), en la sección 5.1, establece:

> *"Handshake messages MAY be fragmented over several TLSPlaintext records."*
> *"The server MUST reassemble fragmented handshake messages before processing."*

Traducido: **un mismo mensaje handshake puede enviarse partido en múltiples registros TLS**. El servidor está obligado a reensamblarlos antes de procesarlos.

Esto existe por razones prácticas: un ClientHello puede ser muy grande (con muchas cipher suites y extensiones), y los implementadores necesitan poder fragmentarlo.

### 4.3 Por qué el DPI no puede reensamblar tan fácilmente

El DPI tiene restricciones de diseño:
- **Tiempo limitado**: debe tomar decisiones en microsegundos para no ser un cuello de botella
- **Memoria limitada**: no puede bufferear paquetes indefinidamente para cada conexión simultánea
- **Ventana de análisis**: tiene un tiempo máximo en el que espera más datos antes de tomar una decisión o descartar el contexto

Si el ClientHello llega partido en fragmentos con suficiente separación temporal, el DPI puede:
- Tomar una decisión con el primer fragmento (que no tiene SNI) → permitir
- O agotar su ventana de análisis esperando el ClientHello completo → dejar pasar por timeout

---

## 5. TLS Record Splitting — la técnica del bypass

### 5.1 La idea

En vez de enviar el ClientHello en un solo TLS Record (que el DPI lee completo y bloquea), lo partimos en **múltiples registros TLS válidos** con **delays entre ellos**.

```
ANTES (un solo registro):
[Header TLS][...ClientHello completo con SNI "chatgpt.com"...]
                                          ↑
                                    DPI lee esto → BLOQUEA

DESPUÉS (tres registros separados en el tiempo):
t=0ms    [Header TLS][1 byte del payload]
t=30ms   [Header TLS][payload hasta 10 bytes antes del SNI]
t=80ms   [Header TLS][SNI "chatgpt.com" + resto]
                             ↑
              El DPI ya descartó el contexto de análisis
              No puede leer esto → PASA
```

Cada fragmento es un **TLS Record válido** por sí mismo (tiene su propio header de 5 bytes). El servidor destino los reensambla correctamente. El DPI del firewall no puede.

### 5.2 Por qué 3 fragmentos y no 2

Esto lo determinamos experimentalmente observando los logs:

**Con 2 fragmentos / 5ms de delay:**
```
[AI] CONNECT chatgpt.com:443 -> 104.18.32.47:443
  [FRAG] TLS ClientHello (1785 bytes)
    -> Record1: 1474B | Record2: 316B
  [TIMEOUT] chatgpt.com   ← el DPI bufereó ambos y aun bloqueó
```

**Con 3 fragmentos / 30ms+50ms:**
```
[AI] CONNECT chatgpt.com:443 -> 104.18.32.47:443
  [FRAG] TLS ClientHello (1785 bytes)
    3-frag: R1=6B | R2=170B | R3=1620B
  ← sin error = EXITOSO
```

El cambio de `ECONNRESET` (DPI manda RST al ver SNI) a `TIMEOUT` con 2 fragmentos ya confirmó que el DPI dejaba de ver el SNI. El `TIMEOUT` indicaba que el PaloAlto App-ID seguía identificando el tráfico por otro mecanismo. Los 3 fragmentos con mayor separación temporal superaron esa segunda capa.

### 5.3 Implementación en el código

```javascript
async function fragmentTLSClientHello(socket, data) {
  socket.setNoDelay(true);  // deshabilita el algoritmo Nagle
                             // (que combina paquetes pequeños en uno solo)

  const version   = (data[1] << 8) | data[2];   // versión TLS del record
  const recordLen = (data[3] << 8) | data[4];   // longitud del payload
  const payload   = data.slice(5, 5 + recordLen); // el ClientHello completo

  // Encontrar dónde está el SNI para cortar antes de él
  const sniAbs = findSNIOffset(data);
  const cut2 = Math.max(2, sniAbs - 5 - 10);  // 10 bytes antes del SNI
  const cut1 = 1;                               // primer byte del payload

  // Partir el payload en 3 partes
  const part1 = payload.slice(0, cut1);         // 1 byte
  const part2 = payload.slice(cut1, cut2);      // hasta antes del SNI
  const part3 = payload.slice(cut2);            // SNI + resto

  // Construir 3 TLS Records válidos
  const r1 = buildTlsRecord(version, part1);
  const r2 = buildTlsRecord(version, part2);
  const r3 = buildTlsRecord(version, part3);

  // Enviarlos con delays
  socket.write(r1);
  await delay(30);           // ← esperar 30ms
  if (socket.destroyed) return;

  socket.write(r2);
  await delay(50);           // ← esperar 50ms más (80ms total desde R1)
  if (socket.destroyed) return;

  socket.write(r3);          // ← SNI llega cuando el DPI ya cerró su ventana
  socket.setNoDelay(false);  // restaurar Nagle para el resto de la conexión
}

// Construir un TLS Record con su header de 5 bytes
function buildTlsRecord(version, slice) {
  const rec = Buffer.alloc(5 + slice.length);
  rec[0] = 0x16;                          // ContentType: Handshake
  rec[1] = (version >> 8) & 0xff;         // TLS version (high byte)
  rec[2] = version & 0xff;                // TLS version (low byte)
  rec[3] = (slice.length >> 8) & 0xff;   // payload length (high byte)
  rec[4] = slice.length & 0xff;           // payload length (low byte)
  slice.copy(rec, 5);                     // el payload
  return rec;
}
```

**¿Por qué `setNoDelay(true)`?** Node.js usa el algoritmo de Nagle por defecto, que combina escrituras pequeñas en un solo paquete TCP para ser más eficiente. Si enviamos los 3 records sin desactivar Nagle, podrían llegar juntos en el mismo segmento TCP y el DPI los vería como si fueran uno. Con `setNoDelay(true)` cada `socket.write()` se convierte en un segmento TCP separado.

---

## 6. DNS-over-HTTPS (DoH)

### 6.1 El problema con DNS normal

El DNS tradicional viaja en texto claro por el puerto UDP 53. Un router corporativo puede:
- Interceptar las consultas y responder con IPs falsas (DNS Hijacking)
- Bloquear respuestas para ciertos dominios
- Observar qué sitios visitás

### 6.2 Cómo funciona DoH

**DNS-over-HTTPS** envía las consultas DNS dentro de una conexión HTTPS normal. Para el router, parece una petición HTTPS cualquiera a `dns.google` o `cloudflare-dns.com`.

```
Consulta DNS normal (bloqueada):
UDP puerto 53: "¿IP de chatgpt.com?"   ← texto claro, interceptable

Consulta DoH (no bloqueada):
HTTPS a https://dns.google/resolve?name=chatgpt.com&type=A
↑ cifrada, parece tráfico normal al servidor de Google
```

La respuesta es JSON:
```json
{
  "Answer": [
    { "name": "chatgpt.com", "type": 1, "data": "104.18.32.47" },
    { "name": "chatgpt.com", "type": 1, "data": "172.64.155.209" }
  ]
}
```

### 6.3 Implementación

```javascript
const DOH_SERVERS = [
  'https://dns.google/resolve',
  'https://cloudflare-dns.com/dns-query',
];

async function dohResolve(hostname) {
  for (const server of DOH_SERVERS) {        // intenta Google primero, luego Cloudflare
    const url = `${server}?name=${encodeURIComponent(hostname)}&type=A`;

    const response = await fetch(url, {
      headers: { accept: 'application/dns-json' },
      timeout: 4000,
    });

    const json = await response.json();
    const ips = (json.Answer || [])
      .filter(a => a.type === 1)              // type 1 = registro A (IPv4)
      .map(a => a.data);

    if (ips.length > 0) {
      AI_DOMAINS[hostname] = ips;             // cachear para próximas conexiones
      return ips[0];
    }
  }
  return null;
}
```

**¿Por qué IPs hardcodeadas además de DoH?** El DoH tarda ~100-200ms en resolver. Si el primer paquete llega antes de que resuelva, el proxy conecta al hostname (DNS del sistema = bloqueado). Con IPs hardcodeadas para los dominios principales, la resolución es instantánea.

---

## 7. El proxy HTTP CONNECT

### 7.1 ¿Qué es un proxy HTTP?

Un proxy es un servidor intermediario. El navegador, en vez de conectarse directamente a `chatgpt.com`, se conecta al proxy y le pide que haga la conexión por él.

Para HTTPS, el protocolo usa el método **HTTP CONNECT**:

```
Browser ──── CONNECT chatgpt.com:443 HTTP/1.1 ──── ► Proxy (127.0.0.1:8889)
Browser ◄──── HTTP/1.1 200 Connection Established ── Proxy

Browser ──── [TLS ClientHello cifrado] ──────────── ► Proxy ──► chatgpt.com
Browser ◄──── [TLS ServerHello...] ──────────────── Proxy ◄── chatgpt.com
```

El proxy actúa como un tubo transparente entre el browser y el servidor. Todo lo que envía el browser pasa al servidor y viceversa, sin que el proxy pueda leer el contenido (porque está cifrado con TLS).

### 7.2 Por qué esto nos ayuda

El browser le dice al proxy "quiero conectarme a `chatgpt.com:443`". El proxy:
1. Resuelve `chatgpt.com` a la IP real (vía hardcode o DoH), evitando el DNS del router
2. Conecta directamente a esa IP
3. Intercepta el TLS ClientHello
4. Lo fragmenta en 3 registros con delays
5. El DPI del firewall ve los fragmentos pero no puede reconstruir el ClientHello

Sin el proxy, el browser conectaría directamente con el DNS del router (bloqueado) y el DPI vería el ClientHello completo.

### 7.3 Flujo completo

```javascript
server.on('connect', async (req, clientSocket, head) => {
  const [hostname, portStr] = req.url.split(':');
  const port = parseInt(portStr) || 443;

  // 1. Resolver IP (hardcode o DoH)
  const { ip, isAI } = await resolveHost(hostname);

  // 2. Conectar al servidor real por IP (no por hostname)
  const serverSocket = net.connect({ host: ip, port }, () => {

    // 3. Confirmar al browser que el túnel está listo
    clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');

    let firstPacket = true;

    // 4. Interceptar el primer paquete del browser (será el TLS ClientHello)
    clientSocket.on('data', (chunk) => {
      if (firstPacket && isAI && SPLIT_ENABLED) {
        firstPacket = false;
        fragmentTLSClientHello(serverSocket, chunk);  // 5. Fragmentar
      } else {
        serverSocket.write(chunk);  // resto del tráfico: pasar directo
      }
    });

    // 6. El tráfico del servidor al browser va directo (ya está cifrado)
    serverSocket.on('data', chunk => clientSocket.write(chunk));
  });
});
```

---

## 8. Resumen visual del sistema completo

```
┌─────────────────────────────────────────────────────────────────┐
│  NAVEGADOR                                                       │
│  Configurado con proxy 127.0.0.1:8889                            │
└──────────────────────┬──────────────────────────────────────────┘
                       │ HTTP CONNECT chatgpt.com:443
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  ai-proxy.js  (127.0.0.1:8889)                                  │
│                                                                  │
│  resolveHost("chatgpt.com")                                     │
│    → Busca en AI_DOMAINS["chatgpt.com"]                         │
│    → Encuentra: ["104.18.32.47", "104.18.35.47"]                │
│    → Retorna: { ip: "104.18.32.47", isAI: true }                │
│                                                                  │
│  net.connect("104.18.32.47", 443)  ← IP directa, no hostname   │
│                                                                  │
│  Responde al browser: "200 Connection Established"              │
│                                                                  │
│  Recibe TLS ClientHello del browser (1785 bytes)                │
│  fragmentTLSClientHello():                                       │
│    findSNIOffset() → SNI está en byte 1484                      │
│    cut1 = 1, cut2 = 1474                                        │
│    R1 = bytes[0..1]     →  write(R1)                           │
│                         →  await delay(30ms)                    │
│    R2 = bytes[1..1474]  →  write(R2)                           │
│                         →  await delay(50ms)                    │
│    R3 = bytes[1474..]   →  write(R3)                           │
└─────────────────────┬───────────────────────────────────────────┘
                      │
          Red corporativa (Checkpoint + PaloAlto)
                      │
         t=0ms:   [Record1: 6B]     → DPI abre contexto
         t=30ms:  [Record2: 1479B]  → DPI acumula, no ve SNI
         t=80ms:  [Record3: 311B]   → DPI ya descartó contexto ✓
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│  Cloudflare / Anthropic                                          │
│  Reensambla R1+R2+R3 = ClientHello completo (RFC 8446)          │
│  Responde con ServerHello, Certificate, etc.                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 9. Herramientas de diagnóstico incluidas

### `test-ips.js`
Consulta múltiples servidores DoH (Google, Cloudflare, Quad9, AdGuard) para obtener todas las IPs disponibles de cada dominio y luego prueba conectividad TCP:443 a cada una.

Útil cuando el DPI empieza a bloquear las IPs actuales y necesitás encontrar alternativas.

### `test-tls.js`
Hace una conexión TLS real (no solo TCP) a cada IP, en dos modos:
- **Con SNI**: simula lo que hace el browser normalmente
- **Sin SNI**: si pasa sin SNI pero falla con SNI → el bloqueo es 100% SNI-based

Útil para confirmar que el problema es el SNI y no la IP.

```
IP                 Dominio          con SNI    sin SNI
104.18.32.47       chatgpt.com      OK         HANDSHAKE_FAILURE
160.79.104.10      claude.ai        OK         HANDSHAKE_FAILURE
```

`HANDSHAKE_FAILURE` en "sin SNI" es normal — Cloudflare rechaza conexiones sin SNI por diseño. Lo importante es que "con SNI" diga `OK` (la IP es alcanzable).
