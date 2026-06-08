# Deep Packet Inspection (DPI) y Bloqueo por SNI

## Que es DPI

Deep Packet Inspection es una tecnica de inspeccion de red que analiza no solo los headers de los paquetes (origen, destino, puerto) sino tambien el **contenido** de los paquetes. Esto permite a firewalls y routers identificar y bloquear trafico basado en el protocolo de aplicacion.

## Que es SNI (Server Name Indication)

SNI es una extension del protocolo TLS que permite al cliente indicar a que dominio quiere conectarse **antes** de que se establezca la encriptacion.

### Por que existe SNI

Un servidor web puede alojar multiples dominios en la misma IP. Cuando un cliente se conecta por HTTPS, el servidor necesita saber cual certificado TLS presentar. SNI resuelve esto enviando el nombre del dominio en el **primer mensaje** del handshake TLS (ClientHello), que viaja **sin encriptar**.

### Donde esta el SNI en el paquete

```
Paquete TCP:
┌─────────────────────────────────────────────────────┐
│ IP Header (src, dst, ...)                            │
│ TCP Header (ports, seq, ...)                         │
│ ┌─────────────────────────────────────────────────┐ │
│ │ TLS Record Header                                │ │
│ │   Content Type: 0x16 (Handshake)                 │ │
│ │   Version: 0x0301                                │ │
│ │   Length: N                                      │ │
│ │ ┌─────────────────────────────────────────────┐ │ │
│ │ │ Handshake: ClientHello                       │ │ │
│ │ │   Version: TLS 1.2                           │ │ │
│ │ │   Random: 32 bytes                           │ │ │
│ │ │   Session ID: variable                       │ │ │
│ │ │   Cipher Suites: [lista]                     │ │ │
│ │ │   Compression Methods: [lista]               │ │ │
│ │ │   Extensions:                                │ │ │
│ │ │     ┌─────────────────────────────────┐     │ │ │
│ │ │     │ Extension: SNI (type 0x0000)    │     │ │ │
│ │ │     │   Server Name List:             │     │ │ │
│ │ │     │     Type: host_name (0)         │     │ │ │
│ │ │     │     Name: "steampowered.com" ◄──┼── VISIBLE PARA DPI
│ │ │     └─────────────────────────────────┘     │ │ │
│ │ │     Extension: supported_versions           │ │ │
│ │ │     Extension: key_share                    │ │ │
│ │ │     ...                                     │ │ │
│ │ └─────────────────────────────────────────────┘ │ │
│ └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

## Como bloquea el DPI

1. El firewall captura todos los paquetes TCP al puerto 443
2. Busca el byte `0x16` al inicio del payload (indica TLS Handshake)
3. Parsea el TLS ClientHello
4. Extrae el campo SNI
5. Si el SNI contiene un dominio bloqueado (`*steam*`), **descarta el paquete** o envia un RST

### Prueba definitiva de bloqueo SNI en este proyecto

```bash
# Sin SNI (solo IP) → FUNCIONA en 0.03s
curl -k https://23.57.121.205/
# Resultado: HTTP 400 (responde, no sabe que dominio servir)

# Con SNI "steampowered.com" → BLOQUEADO (timeout 10s)
curl --resolve store.steampowered.com:443:23.57.121.205 \
     https://store.steampowered.com
# Resultado: timeout - el DPI descarta el paquete
```

Esta prueba demostro inequivocamente que el bloqueo no era por IP (la IP responde) ni por DNS (resolvimos manualmente), sino por el contenido SNI dentro del TLS ClientHello.

## Tecnicas de bypass SNI

### 1. Fragmentacion TCP (parcialmente efectiva)

Dividir el paquete TCP que contiene el ClientHello en multiples segmentos TCP pequenos.

```
Original:  [TCP segment: ClientHello completo con SNI]

Fragmentado: [TCP seg 1: primeros 2 bytes]
             [TCP seg 2: bytes 3-165]
             [TCP seg 3: bytes 166-end (contiene SNI)]
```

**Requiere**: `socket.setNoDelay(true)` para desactivar el algoritmo de Nagle. Sin esto, Node.js combina las escrituras en un solo segmento TCP, anulando la fragmentacion.

**Resultado en este proyecto**: No funciono consistentemente. El DPI del router podia reensamblar los segmentos TCP (reensamblaje TCP es una operacion estandar de redes).

### 2. TLS Record Splitting (la solucion que funciono)

En lugar de fragmentar a nivel TCP, se fragmenta a nivel del **protocolo TLS**. Se divide un registro TLS en dos registros TLS validos, cada uno con su propio header.

Ver documento `05-TLS-RECORD-SPLITTING.md` para el detalle completo.

### 3. Encrypted Client Hello / ECH (no usado)

TLS 1.3 introduce ECH que encripta el SNI. Pero requiere soporte del servidor y del cliente, y no esta ampliamente desplegado.

### 4. Domain Fronting (no usado)

Usar un dominio permitido en el SNI pero un dominio diferente en el header HTTP Host (dentro del tunel encriptado). Requiere un CDN que lo soporte (la mayoria ya lo bloquean).

### 5. VPN/Tunnel (solucion original, limitada)

ProtonVPN encriptaba todo el trafico, evitando el DPI. Pero tenia limite de datos/velocidad.

## Conceptos clave aprendidos

### Algoritmo de Nagle
TCP normalmente agrupa escrituras pequeñas en un solo segmento para eficiencia (Nagle's algorithm). Para que la fragmentacion funcione, DEBE desactivarse con `socket.setNoDelay(true)`. Sin esto, `socket.write(2 bytes)` seguido de `socket.write(300 bytes)` se enviarian como un solo segmento de 302 bytes.

### Reensamblaje TCP vs TLS
- **TCP reassembly**: Los firewalls/DPI modernos pueden reensamblar segmentos TCP. Es una operacion basica de redes. Por eso la fragmentacion TCP sola no funciono.
- **TLS record reassembly**: Requiere que el DPI entienda el protocolo TLS a nivel de registros. La mayoria de DPI solo buscan patrones en el primer registro TLS, no reensamblando multiples registros. Por eso TLS Record Splitting SI funciono.

### HTTP proxy CONNECT method
El metodo HTTP CONNECT crea un tunel TCP transparente a traves del proxy:
```
Cliente → CONNECT store.steampowered.com:443 HTTP/1.1
Proxy   → (conecta a la IP del destino)
Proxy   → HTTP/1.1 200 Connection Established
Cliente → (envia TLS ClientHello a traves del tunel)
Proxy   → (reenvía, aplicando fragmentación si es Steam)
```
El proxy nunca ve el contenido encriptado. Solo maneja el tunel TCP y puede manipular como se envian los bytes al servidor destino.
