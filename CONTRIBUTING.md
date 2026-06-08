# Guía de contribución

Este documento explica cómo está organizado el proyecto, qué hace cada parte, y cómo contribuir de forma efectiva.

---

## Entender el problema antes de tocar el código

Antes de modificar cualquier cosa, es fundamental entender por qué el proxy existe y qué hace. Leer en este orden:

1. **`README.md`** — panorama general, el problema y la solución
2. **`docs/10-AI-PROXY.md`** — arquitectura específica del proxy de IA
3. **`docs/11-CONCEPTOS-TECNICOS.md`** — cada concepto técnico explicado desde cero con el código que lo aplica
4. **`docs/04-DPI-SNI-BLOCKING.md`** y **`docs/05-TLS-RECORD-SPLITTING.md`** — la técnica central

Sin ese contexto, es fácil "optimizar" algo y romper el bypass sin entender por qué dejó de funcionar.

---

## Estructura del proyecto

```
C:\workspace\proxy\
│
├── ai-proxy.js           # Proxy Node.js para ChatGPT + Claude (puerto 8889)
├── steam-proxy.js        # Proxy Node.js para Steam (puerto 8888)
├── start-ai-proxy.ps1    # Launcher PowerShell para el proxy de IA
├── start-proxy.ps1       # Launcher PowerShell para Steam
│
├── test-ips.js           # Herramienta: escanea IPs disponibles via múltiples DoH
├── test-tls.js           # Herramienta: prueba TLS con/sin SNI a una IP concreta
│
├── src/                  # Versión Java (steam-unblock CLI con GraalVM native)
│   └── main/java/com/steamunblock/
│       ├── proxy/        # ConnectTunnelHandler, HttpRequestHandler
│       └── util/         # Log, DnsOverHttps, TlsRecordSplitter
│
├── docs/
│   ├── 01..09-*.md       # Documentación del proxy Steam original
│   ├── 10-AI-PROXY.md    # Documentación del proxy de IA
│   └── 11-CONCEPTOS-TECNICOS.md  # Conceptos técnicos explicados desde cero
│
└── fix-lancache.bat      # Fix de redirección LANCache para Steam
```

---

## Cómo funciona `ai-proxy.js` (los puntos críticos)

### 1. Resolución de dominios (líneas ~20–80)

```javascript
const AI_DOMAINS = {
  'chatgpt.com': ['104.18.32.47', '104.18.35.47'],
  // ...
};
```

Las IPs están **hardcodeadas intencionalmente**. El DNS del sistema está comprometido en la red corporativa target. Si querés agregar un dominio nuevo:

1. Resolvé la IP real fuera de la red bloqueada (o usá `test-ips.js`)
2. Agregala al mapa `AI_DOMAINS`
3. Si tiene variantes de subdominio, agregá el patrón a `AI_PATTERNS`

### 2. Fragmentación TLS (función `fragmentTLSClientHello`)

Esta función es el corazón del bypass. **No cambies los delays sin probar en la red bloqueada**.

```
delay(30ms) entre R1 y R2
delay(50ms) entre R2 y R3
```

Estos valores NO son arbitrarios. Son el resultado de un proceso de prueba:

| Configuración | Resultado | Por qué |
|---|---|---|
| Sin fragmentación | ECONNRESET | DPI ve SNI completo |
| 2 fragmentos / 5ms | TIMEOUT | DPI buferea todo dentro de su ventana |
| 3 fragmentos / 30ms+50ms | **Conexión exitosa** | Supera la ventana de análisis del DPI |

El umbral exacto depende del modelo de firewall y su configuración. Si cambiás los delays, documentá el entorno donde probaste.

### 3. `socket.setNoDelay(true)`

Crítico. Sin esto, el sistema operativo puede fusionar los tres `socket.write()` en un solo segmento TCP, haciendo inútil la fragmentación. Se restaura a `false` después de enviar el ClientHello.

---

## Cómo agregar soporte para un dominio nuevo

### Opción A — Dominio conocido (IP fija)

Editá `AI_DOMAINS` en `ai-proxy.js`:

```javascript
const AI_DOMAINS = {
  // ... existentes ...
  'nuevo-dominio.com': ['IP1', 'IP2'],
};
```

Para encontrar la IP real:
```bash
node test-ips.js
# o manualmente:
curl "https://dns.google/resolve?name=nuevo-dominio.com&type=A"
```

### Opción B — Familia de subdominios (patrón)

Si hay muchos subdominios dinámicos (ej. `ab.chatgpt.com`, `s0.chatgpt.com`, etc.):

```javascript
const AI_PATTERNS = ['openai', 'chatgpt', 'anthropic', 'claude', 'nuevo-patron'];
```

Los dominios que coincidan por patrón se resuelven via DoH y se cachean en memoria.

---

## Cómo diagnosticar un bloqueo nuevo

### Paso 1 — Identificar la capa de bloqueo

```powershell
# Sin fragmentación — si da ECONNRESET, el DPI ve el SNI
.\start-ai-proxy.ps1 -NoSplit

# Con fragmentación normal — si da TIMEOUT, el DPI no ve SNI pero hay otra capa
.\start-ai-proxy.ps1
```

| Síntoma | Diagnóstico | Solución |
|---|---|---|
| `ECONNRESET` en -NoSplit | DPI ve SNI completo | La fragmentación debería resolverlo |
| `ECONNRESET` con fragmentación | Bloqueo por IP | Probar IPs alternativas con `test-ips.js` |
| `TIMEOUT` con fragmentación | App-ID o IP blocking | Aumentar delays o buscar IPs alternativas |
| Conecta pero la página no carga | SNI pass, but content blocked | Dominio sub-bloqueado a nivel HTTP |

### Paso 2 — Probar IPs alternativas

```bash
node test-ips.js
```

Consulta Google, Cloudflare, Quad9 y AdGuard. Algunos rangos de IP pueden estar menos bloqueados que otros.

### Paso 3 — Confirmar que el TLS funciona fuera de la red

```bash
node test-tls.js
```

Verifica que la IP realmente acepta TLS. Útil para descartar problemas propios antes de culpar al firewall.

---

## Variables de entorno

| Variable | Valores | Efecto |
|---|---|---|
| `NO_SPLIT` | `"1"` | Desactiva la fragmentación TLS (modo diagnóstico) |
| `PORT` | número | Puerto del proxy (default: `8889`) |

Ejemplo de uso manual:
```powershell
$env:NO_SPLIT = "1"
node ai-proxy.js
```

---

## Estilo de código

- **Node.js puro** — sin dependencias externas. El proxy usa solo módulos de la stdlib (`net`, `http`, `https`, `url`).
- **No hay tests automáticos** — la validez del código se verifica ejecutándolo contra la red bloqueada.
- **Los logs son el debug principal** — cada conexión loguea dominio, IP destino, modo de fragmentación y resultado.
- **Los delays están en milisegundos y son constantes literales** — no uses variables de configuración para ellos sin documentar por qué.

---

## Antes de hacer un PR

1. Probaste el cambio en la red bloqueada (no solo en red normal)
2. Si cambiaste delays o lógica de fragmentación: documentás el entorno y los resultados observados
3. Si agregaste dominios: incluís de dónde sacaste las IPs (DoH server, fecha)
4. Actualizaste la documentación relevante en `docs/`

---

## Historial de decisiones importantes

Estas decisiones se tomaron después de pruebas concretas. No revertirlas sin probar primero:

| Decisión | Alternativa descartada | Por qué se descartó |
|---|---|---|
| 3 fragmentos con 30ms+50ms | 2 fragmentos con 5ms | TIMEOUT persistente — App-ID seguía bloqueando |
| IPs hardcodeadas en el código | Solo DoH | DoH fallaba en arranque frío (chatgpt.com resolvía al hostname) |
| `setNoDelay(true)` antes de escribir | Confiar en el SO | El SO fusionaba los writes en un solo segmento |
| DoH con dos servidores de fallback | Solo dns.google | Redundancia en caso de que uno sea bloqueado |
| Puerto 8889 para IA, 8888 para Steam | Puerto único | Permiten correr ambos proxies simultáneamente |
