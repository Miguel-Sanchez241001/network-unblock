# Arquitectura del Proxy

## Vision general

```
                    ┌──────────────────────────────────┐
                    │     steam-proxy.js (Node.js)      │
                    │        127.0.0.1:8888              │
                    ├──────────────────────────────────┤
   Steam/Browser    │                                    │     Internet
   ─────────────►   │  1. Recibe peticion                │
                    │  2. Detecta si es dominio Steam    │
                    │  3. Si Steam:                      │
                    │     a. Resuelve IP via DoH          │  ──────────►  dns.google
                    │     b. Conecta a IP real            │  ──────────►  Servidor Steam
                    │     c. Fragmenta TLS ClientHello   │
                    │  4. Si no Steam:                   │
                    │     a. Pasa sin modificar           │  ──────────►  Internet normal
                    └──────────────────────────────────┘
```

## Componentes del proxy

### 1. Mapa de dominios hardcodeados

```javascript
const STEAM_DOMAINS = {
  'store.steampowered.com': ['23.57.121.205'],
  'api.steampowered.com':   ['96.6.206.56'],
  // ... 19 dominios mapeados
};
```

- IPs resueltas previamente via Google DNS (dns.google/resolve)
- Especificas para la region Sudamerica
- Eliminan la necesidad de consulta DNS al router

### 2. Resolver DNS con fallback DoH

```
Peticion para "hostname":
  1. Buscar en STEAM_DOMAINS hardcodeados → retorna IP si existe
  2. Si contiene "steam" o "valve" en el nombre:
     → Resolver via DNS-over-HTTPS (dns.google)
     → Cachear resultado para futuras peticiones
  3. Si no es Steam → pasar sin modificar (DNS normal del sistema)
```

La funcion DoH hace un GET a:
```
https://dns.google/resolve?name={hostname}&type=A
```

Parsea el JSON de respuesta y extrae los registros A (IPs).

### 3. Handler HTTP (peticiones no encriptadas)

Para peticiones HTTP simples (como avatares en puerto 80):

```
Cliente → GET http://avatars.steamstatic.com/abc.jpg HTTP/1.1
Proxy   → Resuelve avatars.steamstatic.com → 199.232.211.52
Proxy   → GET /abc.jpg HTTP/1.1
          Host: avatars.steamstatic.com    ← mantiene Host original
          → Envia a 199.232.211.52:80
```

El proxy mantiene el header `Host` original (el servidor lo necesita) pero conecta a la IP resuelta.

### 4. Handler CONNECT (tuneles HTTPS)

Para conexiones HTTPS, el cliente usa el metodo CONNECT:

```
Secuencia:

1. Cliente → CONNECT store.steampowered.com:443 HTTP/1.1
2. Proxy   → Resuelve: store.steampowered.com → 23.57.121.205
3. Proxy   → net.connect(23.57.121.205, 443)    ← TCP handshake
4. Proxy   → HTTP/1.1 200 Connection Established
5. Cliente → [TLS ClientHello con SNI]
6. Proxy   → [Aplica TLS Record Splitting]       ← Fragmenta aqui
7. Proxy   → Envia Record 1 al servidor
8. Proxy   → (5ms delay)
9. Proxy   → Envia Record 2 al servidor
10. Servidor → [TLS ServerHello, Certificate, ...]
11. (tunel TCP bidireccional transparente de aqui en adelante)
```

### 5. Motor de TLS Record Splitting

```javascript
function fragmentTLSClientHello(socket, data) {
    // 1. Activar TCP_NODELAY
    socket.setNoDelay(true);

    // 2. Parsear TLS Record Header
    const version = (data[1] << 8) | data[2];
    const recordLen = (data[3] << 8) | data[4];
    const payload = data.slice(5, 5 + recordLen);

    // 3. Encontrar SNI offset
    const sniOffset = findSNIOffset(data);
    const splitPoint = sniOffset - 5 - 10; // antes del SNI

    // 4. Construir 2 TLS Records
    const record1 = buildTLSRecord(0x16, version, payload.slice(0, splitPoint));
    const record2 = buildTLSRecord(0x16, version, payload.slice(splitPoint));

    // 5. Enviar como TCP segments separados
    socket.write(record1);
    setTimeout(() => socket.write(record2), 5);
}
```

### 6. Deteccion automatica de dominios Steam

```javascript
const STEAM_PATTERNS = ['steam', 'valve'];

// Cualquier dominio que contenga "steam" o "valve"
// se resuelve automaticamente via DoH
const isSteamDomain = STEAM_PATTERNS.some(p =>
    hostname.toLowerCase().includes(p)
);
```

Esto permite detectar dominios no mapeados como `images.steamusercontent.com`, `shared.akamai.steamstatic.com`, etc., y resolverlos via DoH sin necesidad de agregarlos manualmente.

## Configuracion de Steam

### proxyconfig.vdf

```
"proxyconfig"
{
    "proxy_mode"        "2"        // 2 = proxy manual habilitado
    "address"           "http://127.0.0.1"
    "port"              "8888"
    "exclude_local"     "0"        // no excluir conexiones locales
}
```

### Flag -tcp

Steam se lanza con `-tcp` forzando conexiones TCP en vez de UDP para los servidores CM (Client Manager). Esto asegura que las conexiones CM tambien pasen por el proxy CONNECT.

### Proxy del sistema Windows

```
ProxyEnable: 1
ProxyServer: 127.0.0.1:8888
```

Configurado en: Configuracion > Red e Internet > Proxy > Manual

## Flujo completo de una descarga

```
1. Steam CM: "quiero descargar Dota 2"
   → CONNECT ext1-lim1.steamserver.net:443
   → Proxy: DoH resolve → 155.133.244.34
   → Proxy: TLS Record Split
   → CM responde: "usa cache1-lim1.steamcontent.com"

2. Steam verifica LANCache:
   → Resuelve lancache.steamcontent.com
   → Hosts file: 155.133.244.4 (IP publica)
   → Steam: "no es LANCache, usar HTTPS normal"

3. Steam descarga chunks:
   → CONNECT cache1-lim1.steamcontent.com:443
   → Proxy: DoH resolve → 155.133.244.4
   → Proxy: TLS Record Split
   → DPI: no ve SNI → permite
   → GET /depot/373301/chunk/abc123 HTTP/2
   → Chunk descargado exitosamente
   → Repetir para los 9224 chunks del depot
```

## Manejo de errores

| Error | Causa | Manejo |
|---|---|---|
| ENOTFOUND | DNS del sistema falla para dominio no-Steam | Se pasa sin modificar |
| TIMEOUT | DPI bloquea o servidor no responde | Log + cerrar conexion |
| ECONNRESET | Servidor cierra conexion | Log + cerrar ambos sockets |
| EADDRINUSE | Puerto 8888 ya en uso | Mostrar error y salir |
