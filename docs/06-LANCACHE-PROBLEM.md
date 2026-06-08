# El Problema del LANCache Falso

## Que es LANCache

LANCache es un sistema de cache de red local diseñado para organizaciones (empresas, universidades, LAN parties) que permite cachear descargas de Steam (y otros servicios de juegos) en un servidor local, ahorrando ancho de banda.

### Como funciona normalmente

1. Se configura un servidor LANCache en la red local
2. El DNS de la red apunta `lancache.steamcontent.com` al servidor LANCache
3. Steam detecta que `lancache.steamcontent.com` resuelve a una IP privada/local
4. Steam activa el modo "LANCache" y descarga del servidor local en vez de internet
5. El servidor LANCache descarga de Valve la primera vez y cachea para futuras peticiones

### El protocolo LANCache

Cuando Steam detecta un LANCache:
- Cambia de **HTTPS (puerto 443)** a **HTTP (puerto 80)** para las descargas
- Envia requests HTTP GET directos al IP del LANCache
- No usa el proxy HTTP del sistema (conexion directa)

## El problema en este escenario

### Lo que hacia el router

```
DNS Query: lancache.steamcontent.com
             |
    [Router/Firewall DNS]
             |
    Respuesta: 10.0.0.2  (IP del propio equipo del usuario!)
```

El router estaba configurado para devolver `10.0.0.2` para **todos** los dominios `*.steamcontent.com`. Esto tenia el efecto de:

1. Steam resuelve `lancache.steamcontent.com` → obtiene `10.0.0.2`
2. `10.0.0.2` es una IP privada → Steam activa modo LANCache
3. Steam intenta descargar de `http://10.0.0.2:80/depot/373301/chunk/...`
4. No hay servidor LANCache en esa IP → la respuesta es vacia/invalida
5. Steam recibe chunks corruptos → "Unpack failed (c:240,u:0,r:243,b:243)"
6. Download rate: **0.000 Mbps**

### Evidencia en el log de Steam (content_log.txt)

```
[04:08:38] Enabling local content cache at '::ffff:a00:2' from lookup of lancache.steamcontent.com.
[04:08:38] Adding cache type 'LANCache' on host '::ffff:a00:2'

[04:08:38] Failed unpacking chunk "08f7b714..." (Unpack failed (c:240,u:0,r:243,b:243))

[04:09:18] HTTP (SteamCache,159) - cache4-lim1.steamcontent.com
           (10.0.0.2:80 / 10.0.0.2:80, host: cache4-lim1.steamcontent.com):
           Received 0 (Invalid) HTTP response for depot 373301

[04:09:50] Current download rate: 0.000 Mbps
```

### Por que el proxy no solucionaba esto

El proxy HTTP funciona interceptando peticiones HTTP/HTTPS del sistema:

```
Aplicacion → [usa proxy del sistema] → Proxy → Internet
```

Pero en modo LANCache, Steam:
- Hace sus propias consultas DNS (no usa el proxy para DNS)
- Conecta directamente por HTTP puerto 80 al IP del LANCache
- **No pasa por el proxy del sistema**

```
Steam LANCache → [DNS directo al router] → obtiene 10.0.0.2
Steam LANCache → [HTTP directo a 10.0.0.2:80] → FALLA
                  (bypass del proxy)
```

## La solucion

### Archivo hosts

Agregar al archivo `C:\Windows\System32\drivers\etc\hosts`:

```
# Desactiva LANCache falso - apunta a servidor real de Valve en Lima
155.133.244.4    lancache.steamcontent.com
155.133.244.4    cache1-lim1.steamcontent.com
155.133.244.20   cache2-lim1.steamcontent.com
155.133.244.3    cache3-lim1.steamcontent.com
155.133.244.19   cache4-lim1.steamcontent.com
```

### Por que funciona

1. **hosts file se consulta ANTES que el DNS**: Windows (y cualquier OS) revisa el archivo hosts antes de hacer una consulta DNS. La consulta nunca llega al router.

2. **`lancache.steamcontent.com` resuelve a una IP PUBLICA**: `155.133.244.4` no es una IP privada/local, asi que Steam **NO activa** el modo LANCache.

3. **Steam usa HTTPS normal**: Sin LANCache, Steam descarga por HTTPS (puerto 443) usando el proxy del sistema.

4. **El proxy aplica TLS Record Splitting**: Las conexiones HTTPS pasan por el proxy, donde se aplica la fragmentacion TLS para evadir el DPI.

### Flujo corregido

```
Steam: resolver lancache.steamcontent.com
  → hosts file: 155.133.244.4 (IP publica)
  → Steam: "no es IP privada, no hay LANCache"
  → Steam: usar HTTPS normal via proxy del sistema

Steam: CONNECT cache3-lim1.steamcontent.com:443
  → Proxy: resolver via DoH → 155.133.244.3
  → Proxy: conectar a 155.133.244.3:443
  → Proxy: aplicar TLS Record Splitting al ClientHello
  → DPI: no puede leer SNI → permite conexion
  → Steam: descarga exitosa a velocidad normal
```

## SteamPipe: El sistema de descarga de Steam

### Arquitectura

Steam usa **SteamPipe** para distribuir contenido:

1. Los archivos se dividen en **chunks de ~1MB**
2. Cada chunk se comprime y encripta
3. Los chunks se distribuyen via CDN HTTP/HTTPS
4. El cliente descarga chunks individuales y los reensambla

### Protocolo de descarga

- **Sin LANCache**: HTTPS (puerto 443) con HTTP/2
- **Con LANCache**: HTTP (puerto 80) sin encriptacion
- **Formato URL**: `/depot/{depot_id}/chunk/{chunk_hash}`
- **Servidores**: `cache{N}-{region}.steamcontent.com`

### Regiones de servidores

Steam asigna servidores basandose en el **CellID** (identificador de region):
- CellID 12: Atlanta (ATL3) — servidores con VPN US
- CellID 118: Lima (LIM1) — servidores sin VPN, region Peru
- Los servidores de Lima: `cache1-lim1` a `cache7-lim1.steamcontent.com`
- Rango IP Lima: `155.133.244.x` (subnet de Valve)
