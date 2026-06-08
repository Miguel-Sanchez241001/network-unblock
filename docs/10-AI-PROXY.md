# AI Proxy — ChatGPT y Claude en redes corporativas bloqueadas

## Contexto

Este documento describe la extensión del proxy original (Steam) para dar soporte a los dominios de **ChatGPT (OpenAI)** y **Claude (Anthropic)**, en el mismo entorno de red corporativa con bloqueo multicapa: Checkpoint Endpoint Security + PaloAlto NGFW.

---

## Capas de bloqueo identificadas

La red bloquea los servicios de IA mediante tres mecanismos simultáneos:

### 1. DNS Hijacking
El resolver DNS del router intercepta consultas para dominios como `chatgpt.com` y `claude.ai` y devuelve IPs falsas o no responde. El cliente nunca obtiene la IP real del servidor.

### 2. DPI / SNI Inspection
Cada conexión HTTPS comienza con un mensaje **TLS ClientHello**, enviado en texto claro (antes de que el cifrado empiece). Ese mensaje contiene el campo **SNI (Server Name Indication)** — el nombre del sitio de destino. El firewall lee ese campo y si coincide con un dominio bloqueado (chatgpt.com, claude.ai, anthropic.com, etc.) envía un TCP RST, cortando la conexión con `ECONNRESET`.

### 3. IP Blocking (categoría URL)
Algunos rangos de IP de Cloudflare y Anthropic están registrados en la base de datos de categorías del PaloAlto como "AI Tools" o "Personal Communication". Si el DPI no puede identificar por SNI, puede bloquear por IP de destino.

---

## Arquitectura de la solución

```
Browser (Firefox/Chrome)
    │
    │  HTTP CONNECT chatgpt.com:443
    ▼
┌─────────────────────────────────────────┐
│  ai-proxy.js  (127.0.0.1:8889)         │
│                                         │
│  1. Resuelve dominio → IP real          │
│     • Mapa hardcodeado (instantáneo)    │
│     • Fallback DoH a dns.google /       │
│       cloudflare-dns.com                │
│                                         │
│  2. Abre TCP a IP_real:443              │
│                                         │
│  3. Responde "200 Connection            │
│     Established" al browser             │
│                                         │
│  4. Intercepta el TLS ClientHello       │
│     del browser                         │
│                                         │
│  5. Fragmenta en 3 registros TLS        │
│     con delays 30ms / 50ms              │
└─────────────────────────────────────────┘
    │  R1 (1B)  →  30ms  →  R2 (~150B)  →  50ms  →  R3 (resto)
    ▼
┌─────────────────────────────────────────┐
│  Red corporativa                        │
│  Checkpoint Endpoint + PaloAlto NGFW   │
│                                         │
│  Recibe 3 fragmentos TLS separados.    │
│  No logra reensamblar el ClientHello   │
│  dentro de su ventana de análisis.     │
│  → Deja pasar los fragmentos ✓         │
└─────────────────────────────────────────┘
    │
    ▼
Cloudflare / Anthropic
    │  Reensambla los 3 registros (RFC 8446 §5.1)
    │  Completa TLS handshake normalmente
    ▼
chatgpt.com / claude.ai  ✓
```

---

## Bypass 1: DNS (IPs hardcodeadas + DoH)

### Problema
El DNS del router devuelve IPs incorrectas para los dominios de IA.

### Solución
El proxy NO usa el DNS del sistema. Tiene un mapa estático con las IPs reales, resueltas previamente via `dns.google`:

```javascript
const AI_DOMAINS = {
  'chatgpt.com':                 ['104.18.32.47',  '104.18.35.47'],
  'chat.openai.com':             ['104.18.37.228', '104.18.32.228'],
  'api.openai.com':              ['162.159.140.245'],
  'auth.openai.com':             ['104.18.37.228'],
  'platform.openai.com':         ['104.18.37.228'],
  'cdn.oaistatic.com':           ['104.18.41.158'],
  'files.oaiusercontent.com':    ['104.18.32.47'],
  'ws.chatgpt.com':              ['104.18.39.21',  '172.64.148.235'],
  'claude.ai':                   ['160.79.104.10', '160.79.104.11'],
  'www.claude.ai':               ['160.79.104.10'],
  'anthropic.com':               ['160.79.104.10'],
  'api.anthropic.com':           ['160.79.104.10'],
  'console.anthropic.com':       ['160.79.104.10'],
  'cdn.anthropic.com':           ['160.79.104.10'],
  'bridge.claudeusercontent.com':['160.79.104.10'],
};
```

Para subdominios dinámicos no mapeados, el proxy usa **DNS-over-HTTPS** con dos servidores de fallback:

```javascript
const DOH_SERVERS = [
  'https://dns.google/resolve',
  'https://cloudflare-dns.com/dns-query',
];
```

Las consultas DoH viajan cifradas dentro de HTTPS — el router no puede interceptarlas.

---

## Bypass 2: TLS Record Splitting (3 fragmentos)

### Problema
El TLS ClientHello contiene el SNI en texto claro. El DPI lo lee y bloquea.

### Fondo técnico
El protocolo TLS define un concepto llamado **TLS Record**. Un mensaje handshake (como el ClientHello) puede enviarse como un único registro o **fragmentado en múltiples registros**. Esto está explícitamente permitido por el RFC 8446 Sección 5.1:

> "Handshake messages MAY be fragmented over several TLS records."

El servidor destino **debe** reensamblar los fragmentos antes de procesar el mensaje. Los firewalls DPI, sin embargo, tienen un presupuesto de tiempo y memoria limitado para hacer ese reensamblado.

### Solución: 3 registros con delays progresivos

En vez de enviar el ClientHello completo, el proxy lo divide en 3 registros TLS separados:

```
ClientHello original (ej: 1785 bytes de payload):

[TLS Record header 5B][payload 1785B completo]
       └── contiene SNI visible → bloqueado por DPI

↓ fragmentado en:

[Record 1: 6B total]   payload[0..1]      (1 byte)
        ↓ delay 30ms
[Record 2: ~170B]      payload[1..cut2]   (sin SNI)
        ↓ delay 50ms
[Record 3: ~1620B]     payload[cut2..end] (contiene SNI)
```

Donde `cut2 = offset_SNI - 10` (10 bytes antes del campo SNI en el payload).

### Por qué funciona

El DPI del PaloAlto tiene una ventana de análisis (tiempo máximo que espera para reensamblar). Con los 3 fragmentos llegando a 30ms y 80ms de distancia:

1. Llega Record 1 (1 byte) — el DPI abre un contexto de análisis, espera más datos
2. Llega Record 2 a los 30ms — el DPI acumula, todavía no ve el SNI completo
3. El DPI supera su ventana de análisis o descarta el contexto
4. Llega Record 3 a los 80ms — ya no hay contexto activo de análisis, pasa libre

### Por qué 2 fragmentos no bastó

Con 2 fragmentos y 5ms de delay (primera versión), el comportamiento observado fue:
- Sin fragmentación → `ECONNRESET` inmediato (DPI ve SNI, manda RST)
- 2 fragmentos / 5ms → `TIMEOUT` (DPI buferea ambos, igual los bloquea)
- **3 fragmentos / 30ms+50ms → conexión exitosa** ✓

El paso de `ECONNRESET` a `TIMEOUT` con 2 fragmentos ya confirmaba que el DPI no podía ver el SNI. El `TIMEOUT` indicaba que otro mecanismo (App-ID por comportamiento o IP) lo bloqueaba igual. Los 3 fragmentos con mayor separación temporal superan ese segundo mecanismo.

---

## Patrones de dominio activos

El proxy detecta dominios de IA por dos métodos:

### Mapa exacto (prioridad 1)
Busca el hostname en `AI_DOMAINS`. Si existe, usa la IP hardcodeada directamente.

### Patrones substring (prioridad 2, fallback)
Si el hostname no está en el mapa, verifica si contiene alguno de estos patrones:

| Patrón | Dominios que captura |
|---|---|
| `openai` | `chat.openai.com`, `api.openai.com`, `auth.openai.com`, etc. |
| `chatgpt` | `chatgpt.com`, `ws.chatgpt.com`, `ab.chatgpt.com`, etc. |
| `anthropic` | `anthropic.com`, `api.anthropic.com`, `console.anthropic.com` |
| `claude` | `claude.ai`, `bridge.claudeusercontent.com` |
| `oaistatic` | `cdn.oaistatic.com` |
| `oaiusercontent` | `files.oaiusercontent.com` |

Los dominios detectados por patrón se resuelven via DoH y se cachean en memoria para conexiones futuras.

---

## Archivos del proyecto

```
C:\workspace\proxy\
├── ai-proxy.js           # Proxy principal para ChatGPT + Claude
├── start-ai-proxy.ps1    # Launcher PowerShell
│                           Uso: .\start-ai-proxy.ps1
│                           Diagnóstico (sin split): .\start-ai-proxy.ps1 -NoSplit
├── test-ips.js           # Escanea IPs alternativas via múltiples DoH
├── test-tls.js           # Prueba TLS real con/sin SNI por IP
└── steam-proxy.js        # Proxy original para Steam (puerto 8888)
```

---

## Uso

### Inicio normal
```powershell
.\start-ai-proxy.ps1
```

### Modo diagnóstico (sin fragmentación TLS)
```powershell
.\start-ai-proxy.ps1 -NoSplit
```
Útil para comparar comportamiento del DPI. Si en este modo las conexiones pasan, el bloqueo es puramente por SNI y la fragmentación lo resuelve.

### Configuración del navegador
Configurar proxy HTTP manual:
- **Host:** `127.0.0.1`
- **Puerto:** `8889`

El puerto `8888` sigue reservado para el proxy de Steam.

---

## Proceso de diagnóstico usado

| Test | Resultado | Conclusión |
|---|---|---|
| Proxy sin IPs hardcodeadas | `chatgpt.com → chatgpt.com:443` (hostname, no IP) | DoH fallaba en arranque frío |
| Proxy con IPs hardcodeadas, sin split | `ECONNRESET` inmediato | DPI ve SNI completo → RST |
| Proxy con 2 fragmentos / 5ms | `TIMEOUT` | DPI no ve SNI pero bloquea por App-ID/IP |
| Proxy con 3 fragmentos / 30ms+50ms | **Conexión exitosa** ✓ | Supera ventana de análisis del DPI |
| TLS directo con SNI (desde red no bloqueada) | `OK` para todas las IPs | Las IPs son alcanzables, el bloqueo es en la red corporativa |
