# Analisis de Red - Captura Wireshark

## Metodologia de analisis

El punto de partida fue un archivo `analisis.txt` de **1,884,876 lineas** — una exportacion de texto completa de Wireshark capturada a traves de la interfaz ProtonVPN.

### Datos de la captura

| Campo | Valor |
|---|---|
| Duracion | ~14 minutos 53 segundos |
| Inicio | 2026-03-14 01:51:35 (hora local) |
| Fin | 2026-03-14 02:06:29 |
| Total frames | 13,645 |
| Interfaz | ProtonVPN (tunnel VPN) |
| IP local | 10.2.0.2 |
| Gateway VPN/DNS | 10.2.0.1 |

### Proceso de analisis

1. **Identificacion del formato**: Se determino que era una exportacion de Wireshark en texto plano (no PCAP), con detalle completo paquete por paquete incluyendo headers, payloads y hex dumps.

2. **Busqueda de patrones Steam**: Se buscaron los patrones `steam`, `valve`, `steampowered`, `steamcontent`, `steamcdn`, `akamai` en todo el archivo.

3. **Extraccion de consultas DNS**: Se identificaron todas las consultas y respuestas DNS relacionadas con Steam.

4. **Rastreo de cadenas CNAME**: Se siguieron las cadenas de alias DNS para encontrar las IPs finales.

5. **Verificacion de conexiones reales**: Se verifico si habia conexiones TCP/TLS reales a las IPs encontradas (no solo DNS).

6. **Analisis de volumenes de datos**: Se midieron los bytes transferidos para determinar si hubo descarga real.

## Dominios Steam encontrados

### Dominio 1: lancache.steamcontent.com (Descargas)

Cadena DNS completa:
```
lancache.steamcontent.com
  └─ CNAME: origin-tier2.steampipe.steamcontent.com
       └─ CNAME: steampipe-origin-tier2.steamcontent.com
            └─ CNAME: cache-origin.steampipe.steamcontent.akadns.net
                 └─ CNAME: dist-ams1.discovery.steamserver.net
                      ├─ A: 155.133.248.12  (Amsterdam, con VPN)
                      └─ A: 155.133.248.11  (Amsterdam, con VPN)
```

Sin VPN, la misma cadena termina en:
```
dist-eze1.discovery.steamserver.net
  ├─ A: 155.133.255.9   (Buenos Aires)
  └─ A: 155.133.255.8   (Buenos Aires)
```

### Dominio 2: p2p-vie1.discovery.steamserver.net (P2P)

```
p2p-vie1.discovery.steamserver.net
  ├─ A: 146.66.155.54  (Viena)
  └─ A: 146.66.155.38  (Viena)
```

### Conexion activa encontrada

La unica conexion TCP activa de Valve en la captura:
- `10.2.0.2:56839 ↔ 146.66.155.85:443` (TLS 1.2)
- 267 paquetes, ~26 KB transferidos en 14 minutos
- **Diagnostico**: Conexion de control Steam CM (Client Manager), no descarga

## Conclusion del analisis

No se estaba descargando contenido durante la captura. Solo habia una conexion de control (keepalive). Las consultas DNS a servidores de contenido eran periodicas (~60s) pero sin conexiones reales. Esto fue util para entender la infraestructura DNS de Steam pero no para obtener IPs de descarga activas.

## Herramientas de resolucion DNS alternativas

Como el VPN se desconecto y el DNS local estaba bloqueado, se utilizo **Google DNS over HTTPS** como herramienta web externa:

```
https://dns.google/resolve?name=DOMINIO&type=A
```

Esta API publica permite resolver cualquier dominio desde los servidores de Google, completamente independiente del DNS del router local. Fue la herramienta clave para obtener las IPs reales de Steam.
