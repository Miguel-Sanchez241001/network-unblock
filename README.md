# network-unblock

Proxy local para evadir bloqueo DNS + DPI/SNI en redes corporativas. Soporta **Steam**, **ChatGPT** y **Claude** mediante TLS Record Splitting en 3 fragmentos y DNS-over-HTTPS.

Probado contra Checkpoint Endpoint Security + PaloAlto NGFW.

---

## El problema

Las redes corporativas bloquean servicios en tres capas simultáneas:

| Capa | Mecanismo | Síntoma |
|---|---|---|
| DNS Hijacking | El router intercepta consultas DNS y devuelve IPs falsas | El dominio no resuelve o resuelve a IP incorrecta |
| DPI / SNI Inspection | El firewall lee el campo SNI del TLS ClientHello (en texto claro) | `ECONNRESET` inmediato al intentar conectar |
| IP Blocking | Rangos de IP de Cloudflare/Valve en lista negra del firewall | `EHOSTUNREACH` o `TIMEOUT` |

---

## La solución

### Steam → `steam-proxy.js` (puerto 8888)
### ChatGPT + Claude → `ai-proxy.js` (puerto 8889)

Ambos proxies aplican la misma estrategia en tres partes:

#### 1. Bypass DNS — IPs hardcodeadas + DoH
El proxy NO usa el DNS del sistema (que puede estar comprometido). Tiene las IPs reales grabadas directamente. Para dominios desconocidos usa **DNS-over-HTTPS** con dos servidores de fallback (`dns.google` y `cloudflare-dns.com`), cuyas consultas viajan cifradas y no pueden ser interceptadas.

#### 2. Bypass DPI — TLS Record Splitting en 3 fragmentos
El TLS ClientHello contiene el SNI (nombre del sitio) en texto claro. El proxy intercepta ese mensaje y lo parte en **3 registros TLS separados** con delays progresivos:

```
ClientHello original (1 paquete, SNI visible):
[TLS Record: type=0x16, len=1785][...ClientHello con SNI "chatgpt.com"...]
                                                    ↑ DPI lo lee → BLOQUEADO

Fragmentado (3 paquetes con delays):
[Record 1: 6B]      1 byte del payload
      ↓ 30ms
[Record 2: ~170B]   resto hasta antes del SNI
      ↓ 50ms
[Record 3: ~1620B]  SNI + extensiones finales
                            ↑ DPI ya descartó el contexto → PASA ✓
```

Esto es válido según **RFC 8446 §5.1**: el servidor destino DEBE reensamblar los fragmentos. El DPI del firewall no puede porque supera su ventana de análisis.

#### 3. Fix hosts file (solo Steam)
Evita redirecciones LANCache falsas que rompen las descargas de Steam.

---

## Inicio rápido

### Requisitos
- Node.js 18+

### ChatGPT + Claude
```powershell
# Modo normal (con fragmentación TLS)
.\start-ai-proxy.ps1

# Modo diagnóstico (sin fragmentación, para comparar)
.\start-ai-proxy.ps1 -NoSplit
```

### Steam
```powershell
.\start-proxy.ps1
```

### Configurar el navegador
Proxy HTTP manual:
- **Host:** `127.0.0.1`
- **Puerto:** `8889` (AI) o `8888` (Steam)

---

## Archivos

```
├── ai-proxy.js           # Proxy para ChatGPT + Claude  (puerto 8889)
├── steam-proxy.js        # Proxy para Steam             (puerto 8888)
├── start-ai-proxy.ps1    # Launcher PowerShell para AI proxy
├── start-proxy.ps1       # Launcher PowerShell para Steam proxy
├── test-ips.js           # Herramienta: escanea IPs alternativas via DoH
├── test-tls.js           # Herramienta: prueba TLS real con/sin SNI
├── fix-lancache.bat      # Fix para redirección LANCache falsa (Steam)
├── setup-hosts.bat       # Agrega IPs de Steam al archivo hosts
├── remove-hosts.bat      # Remueve entradas del archivo hosts
├── src/                  # Versión Java (steam-unblock CLI con GraalVM native)
├── pom.xml               # Build Maven
└── docs/
    ├── 01-RESUMEN-GENERAL.md     # Resumen del proyecto Steam
    ├── 02-ANALISIS-RED.md        # Análisis de captura Wireshark
    ├── 03-BLOQUEO-DNS.md         # Cómo funciona el bloqueo DNS
    ├── 04-DPI-SNI-BLOCKING.md    # Deep Packet Inspection y bypass SNI
    ├── 05-TLS-RECORD-SPLITTING.md # La técnica de fragmentación TLS
    ├── 06-LANCACHE-PROBLEM.md    # El problema del LANCache falso
    ├── 07-PROXY-ARQUITECTURA.md  # Arquitectura del proxy
    ├── 08-PROCESO-DESARROLLO.md  # Cronología del desarrollo
    ├── 09-GLOSARIO.md            # Glosario técnico
    └── 10-AI-PROXY.md            # Extensión para ChatGPT + Claude ← nuevo
```

---

## Por qué 3 fragmentos y no 2

| Técnica | Resultado observado | Diagnóstico |
|---|---|---|
| Sin proxy | Bloqueado en navegador | DNS hijacking + DPI activos |
| Proxy sin fragmentación | `ECONNRESET` inmediato | DPI ve SNI completo → RST |
| 2 fragmentos / 5ms | `TIMEOUT` (sin RST) | DPI no ve SNI pero bloquea por App-ID |
| **3 fragmentos / 30ms+50ms** | **Conexión exitosa ✓** | Supera la ventana de análisis del DPI |

El cambio de `ECONNRESET` a `TIMEOUT` con 2 fragmentos confirmó que el DPI dejaba de ver el SNI. El `TIMEOUT` indicaba que una segunda capa (PaloAlto App-ID por comportamiento) seguía bloqueando. Los 3 fragmentos con mayor separación temporal superan esa segunda capa.

---

## Versión Java (steam-unblock CLI)

Para Steam también existe una versión Java 21 con binary nativo via GraalVM:

```bash
# Build
mvn clean package

# Ejecutar
java -jar target/steam-unblock-1.0.0.jar proxy

# Con GraalVM (binario nativo, sin JVM)
mvn -Pnative package
./target/steam-unblock proxy
```

---

## Licencia

MIT
