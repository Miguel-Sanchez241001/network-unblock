# Proceso de Desarrollo - Cronologia

## Fase 1: Analisis de red (Captura Wireshark)

### Situacion inicial

El usuario tenia acceso a Steam unicamente a traves de ProtonVPN, que habia alcanzado su limite de datos. Antes de desconectar el VPN, se realizo una captura de red con Wireshark para intentar identificar las IPs de los servidores de Steam.

### Analisis del archivo `analisis.txt`

- **Tamaño**: 1,884,876 lineas (exportacion de texto de Wireshark)
- **Interfaz capturada**: ProtonVPN (10.2.0.2 → gateway 10.2.0.1)
- **Hallazgos**:
  - Dominios encontrados via DNS: `lancache.steamcontent.com`, `p2p-vie1.discovery.steamserver.net`
  - IPs de servidores Steam: 155.133.248.11/12 (Amsterdam), 146.66.155.38/54 (Vienna)
  - Unica conexion activa: `146.66.155.85:443` (Steam CM, ~26KB en 14 min)
  - **Conclusion**: No habia descarga activa durante la captura, solo conexion de control

### Aprendizaje

La captura de Wireshark revelo la cadena de CNAME de Steam y los patrones de conexion, pero no proporciono las IPs de descarga porque no se estaba descargando contenido en ese momento.

## Fase 2: Construccion del proxy v1

### El problema inmediato

Al desconectar el VPN, Steam dejo de funcionar completamente. El router bloqueaba las consultas DNS para dominios Steam.

### Resolucion de IPs reales

Como no se podia resolver dominios Steam desde la red local, se utilizo la API publica de Google DNS:

```
https://dns.google/resolve?name=DOMINIO&type=A
```

Se resolvieron 19 dominios Steam manualmente:
- store.steampowered.com → 23.57.121.205
- api.steampowered.com → 96.6.206.56
- steamcommunity.com → 96.6.206.56
- cdn.cloudflare.steamstatic.com → 200.60.190.10
- avatars.steamstatic.com → 199.232.211.52
- cm0.steampowered.com → 162.254.195.44
- lancache.steamcontent.com → 155.133.255.9
- Y 12 dominios mas

### Proxy v1: DNS bypass basico

Se creo `steam-proxy.js` con:
- Mapa hardcodeado de dominio → IP para 19 dominios Steam
- Servidor HTTP proxy en 127.0.0.1:8888
- Handler HTTP para peticiones no encriptadas
- Handler CONNECT para tuneles HTTPS
- Fragmentacion TCP basica (3 segmentos con delays de 2ms)

### Resultado de pruebas v1

```
HTTP  store.steampowered.com  → 302 OK (funciona)
HTTPS api.steampowered.com    → TIMEOUT (falla)
```

Las peticiones HTTP funcionaban pero HTTPS no. Se desactivo la fragmentacion pensando que era el problema, pero HTTPS seguia fallando.

## Fase 3: Descubrimiento del bloqueo DPI/SNI

### Prueba definitiva

Se ejecutaron dos pruebas con curl que demostraron el bloqueo por SNI:

```bash
# Sin SNI → FUNCIONA (0.03 segundos)
curl -k https://23.57.121.205/
# Resultado: HTTP 400 Bad Request

# Con SNI → BLOQUEADO (timeout)
curl --resolve store.steampowered.com:443:23.57.121.205 \
     https://store.steampowered.com
# Resultado: timeout despues de 10 segundos
```

**Conclusion**: El bloqueo NO era solo DNS. El firewall inspeccionaba el campo SNI dentro del TLS ClientHello y bloqueaba cualquier conexion que contuviera dominios Steam.

### Implicacion

Solo resolver las IPs no era suficiente. Se necesitaba una tecnica para ocultar el SNI del inspector DPI.

## Fase 4: Proxy v2 - Fragmentacion TCP

### Enfoque

Dividir el paquete TCP que contiene el TLS ClientHello en multiples segmentos TCP pequenos, de modo que el DPI no pueda leer el SNI completo en un solo segmento.

### Implementacion

```javascript
socket.setNoDelay(true);  // Desactivar algoritmo de Nagle
socket.write(data.slice(0, 2));      // Segmento 1: 2 bytes
setTimeout(() => {
    socket.write(data.slice(2, 165)); // Segmento 2: bytes 3-165
    setTimeout(() => {
        socket.write(data.slice(165)); // Segmento 3: resto (contiene SNI)
    }, 2);
}, 2);
```

### Resultado

**Parcialmente exitoso**. Algunos dominios funcionaban:
- store.steampowered.com → 200 OK
- api.steampowered.com → 200 OK
- steamcommunity.com → 200 OK

Pero otros fallaban, especialmente los servidores de descarga:
- cache1-lim1.steamcontent.com → TIMEOUT
- cache2-lim1.steamcontent.com → TIMEOUT

### Por que fallo

El DPI realizaba **TCP reassembly** (reensamblaje TCP), una operacion estandar de redes. Los segmentos TCP se reensamblaban en el stream original, y el DPI podia leer el SNI normalmente. La inconsistencia se debia a que algunos paquetes con ClientHello mas grandes tenian el punto de corte en posiciones donde el SNI quedaba legible.

## Fase 5: Proxy v3 - TLS Record Splitting

### El avance conceptual

En lugar de fragmentar a nivel TCP (capa 4), se decidio fragmentar a nivel del **protocolo TLS** (capa 7). Segun RFC 8446, Seccion 5.1:

> "A single handshake message MAY be fragmented over several TLS records."

Esto significa que se puede dividir un ClientHello en dos registros TLS separados, cada uno con su propio header de 5 bytes. El servidor DEBE reensamblarlo, pero el DPI generalmente NO lo hace.

### Diferencia clave

```
Fragmentacion TCP:
  [TCP seg 1][TCP seg 2][TCP seg 3]
  → DPI reensambla en un solo stream → ve SNI → BLOQUEA

TLS Record Splitting:
  [TLS Record 1: header + payload sin SNI]
  [TLS Record 2: header + payload con SNI]
  → DPI ve Record 1 → no encuentra SNI → PERMITE
  → Record 2 ya paso → conexion establecida
```

### Implementacion

1. Parsear el TLS Record original (header de 5 bytes + payload)
2. Encontrar la posicion del SNI dentro del ClientHello (extension type 0x0000)
3. Cortar el payload 10 bytes ANTES del SNI
4. Construir dos TLS Records validos con headers independientes
5. Enviar como segmentos TCP separados con 5ms de delay

### Resultado

```
store.steampowered.com          → 200 OK
api.steampowered.com            → 200 OK
cache1-lim1.steamcontent.com    → 200 OK
cache2-lim1.steamcontent.com    → 200 OK
cache3-lim1.steamcontent.com    → 200 OK
```

100% efectivo contra el DPI del escenario.

## Fase 6: Configuracion de Steam

### Proxy del sistema Windows

```
Configuracion > Red e Internet > Proxy > Manual
Servidor: 127.0.0.1:8888
```

### Proxy interno de Steam

Archivo: `C:\Program Files (x86)\Steam\config\proxyconfig.vdf`

```
"proxyconfig"
{
    "proxy_mode"    "2"
    "address"       "http://127.0.0.1"
    "port"          "8888"
    "exclude_local" "0"
}
```

### Flag TCP

Se agrego `-tcp` al acceso directo de Steam para forzar conexiones TCP en lugar de UDP para los servidores Client Manager. Esto asegura que las conexiones CM pasen por el proxy CONNECT.

### Resultado

Steam conecto exitosamente:
- Login y autenticacion: OK
- Lista de amigos: OK
- Tienda: OK
- Biblioteca: OK

Se observo descubrimiento automatico de dominios Steam no mapeados via DoH:
- ext1-lim1.steamserver.net (CM Lima)
- ext2-lim1.steamserver.net (CM Lima)
- shared.akamai.steamstatic.com
- Y otros

## Fase 7: El problema de las descargas (LANCache)

### Sintomas

Al iniciar la descarga de Dota 2:
- El proxy mostraba conexiones a cache1-lim1, cache2-lim1, cache3-lim1.steamcontent.com
- Estas conexiones hacian TIMEOUT
- Velocidad de descarga: **0.000 Mbps**

### Investigacion

Se analizo `C:\Program Files (x86)\Steam\logs\content_log.txt` y se encontro:

```
Enabling local content cache at '::ffff:a00:2' from lookup of lancache.steamcontent.com.
Adding cache type 'LANCache' on host '::ffff:a00:2'
Failed unpacking chunk "08f7b714..." (Unpack failed (c:240,u:0,r:243,b:243))
```

### Diagnostico

1. El DNS del router devolvia `10.0.0.2` para `lancache.steamcontent.com`
2. `10.0.0.2` es una IP privada → Steam activaba el modo **LANCache**
3. En modo LANCache, Steam cambiaba de HTTPS (443) a HTTP (80)
4. Steam conectaba DIRECTAMENTE a `10.0.0.2:80`, **sin pasar por el proxy**
5. No habia servidor LANCache en esa IP → chunks corruptos → descarga fallida

### La trampa

Aunque se tenia resolucion via hosts file para los servidores `cache*-lim1`, la consulta a `lancache.steamcontent.com` (que Steam hace internamente para detectar LANCache) iba al DNS del router, que devolvia la IP falsa. Steam hacia esta consulta DNS DIRECTAMENTE, sin usar el proxy.

## Fase 8: Solucion final - hosts file fix

### La correccion

Se creo `fix-lancache.bat` que agrega al archivo hosts:

```
155.133.244.4    lancache.steamcontent.com
155.133.244.4    cache1-lim1.steamcontent.com
155.133.244.20   cache2-lim1.steamcontent.com
155.133.244.3    cache3-lim1.steamcontent.com
155.133.244.19   cache4-lim1.steamcontent.com
155.133.244.4    cache5-lim1.steamcontent.com
155.133.244.20   cache6-lim1.steamcontent.com
155.133.244.3    cache7-lim1.steamcontent.com
```

### Por que funciona

1. El archivo hosts se consulta ANTES que el DNS del router
2. `lancache.steamcontent.com` resuelve a `155.133.244.4` (IP publica)
3. IP publica → Steam NO activa modo LANCache
4. Steam usa HTTPS (443) via proxy del sistema
5. El proxy aplica TLS Record Splitting
6. DPI no ve SNI → permite la conexion
7. Descarga exitosa

### Resultado final

Dota 2 comenzo a descargarse a velocidad normal. Las tres capas de bloqueo fueron derrotadas:

| Capa de bloqueo | Tecnica de bypass |
|---|---|
| DNS hijacking | DoH en el proxy + hosts file |
| DPI/SNI inspection | TLS Record Splitting |
| LANCache redirection | Hosts file con IPs publicas reales |

## Cronologia resumida

```
Fase 1: Analisis Wireshark     → Identificar patrones de conexion Steam
Fase 2: Proxy v1 (DNS bypass)  → Funciona HTTP, falla HTTPS
Fase 3: Descubrir bloqueo SNI  → Prueba curl confirma DPI
Fase 4: Proxy v2 (TCP frag)    → Parcial, DPI reensambla TCP
Fase 5: Proxy v3 (TLS split)   → 100% efectivo contra DPI
Fase 6: Config Steam + proxy   → Login, amigos, tienda OK
Fase 7: Descubrir LANCache     → Descargas a 0 Mbps por falso LANCache
Fase 8: Fix hosts file         → Descargas funcionando
```

## Lecciones aprendidas

### 1. Los bloqueos son multi-capa

No basta con resolver un tipo de bloqueo. En este escenario habia tres mecanismos independientes que debian derrotarse simultaneamente.

### 2. TCP reassembly anula la fragmentacion TCP

Los DPI modernos realizan reensamblaje TCP de forma estandar. Fragmentar a nivel TCP no es una tecnica confiable contra DPI.

### 3. TLS Record Splitting es superior a TCP fragmentation

Operar a nivel de protocolo TLS es mucho mas efectivo porque:
- Cada registro TLS tiene su propio header valido
- El DPI necesitaria implementar un parser TLS completo para reensamblar
- La mayoria de DPI solo inspecciona el primer registro TLS

### 4. El algoritmo de Nagle es un enemigo silencioso

Sin `socket.setNoDelay(true)`, Node.js agrupa escrituras consecutivas en un solo segmento TCP, anulando cualquier intento de fragmentacion. Este fue un bug sutil que causo confusions iniciales.

### 5. Las aplicaciones pueden hacer bypass del proxy

Steam en modo LANCache conecta directamente por HTTP, sin usar el proxy del sistema. El archivo hosts fue necesario porque opera a un nivel mas bajo que el proxy.

### 6. Los logs de la aplicacion son invaluables

El archivo `content_log.txt` de Steam revelo exactamente que estaba pasando (activacion de LANCache, chunks corruptos, IPs falsas), algo que no era visible desde el proxy ni desde Wireshark.

### 7. La documentacion tecnica (RFC) es la fuente de verdad

La solucion final (TLS Record Splitting) se basa directamente en RFC 8446 Seccion 5.1, que establece que un handshake message puede fragmentarse en multiples registros TLS. Conocer la especificacion fue clave para diseñar una solucion que funciona por diseño del protocolo, no por exploit.
