# Bloqueo DNS y Tecnicas de Bypass

## Que es el bloqueo DNS

El DNS (Domain Name System) traduce nombres de dominio (`store.steampowered.com`) a direcciones IP (`23.57.121.205`). Un bloqueo DNS intercepta esta traduccion y devuelve una respuesta falsa o nula.

### Como funciona en este escenario

```
[PC] --DNS query: store.steampowered.com?--> [Router/Firewall]
                                                    |
                                          [Inspecciona el dominio]
                                                    |
                                    ┌───────────────┴───────────────┐
                                    |                               |
                              [Dominio Steam]                [Otro dominio]
                                    |                               |
                          [Responde con IP falsa         [Resuelve normalmente]
                           o NXDOMAIN (no existe)]
```

En nuestro caso, el router hacia dos cosas:
1. **Para dominios Steam generales**: No respondia o devolvia error (NXDOMAIN)
2. **Para `*.steamcontent.com`**: Devolvia `10.0.0.2` (IP local) para simular un LANCache

## Tecnicas de bypass DNS

### 1. Archivo Hosts (usado en este proyecto)

El archivo `C:\Windows\System32\drivers\etc\hosts` se consulta ANTES que cualquier servidor DNS. Al agregar entradas aqui, la consulta nunca llega al router.

```
# Ejemplo de entrada en hosts
155.133.244.4    lancache.steamcontent.com
```

**Ventajas**: Simple, sin software adicional, funciona para todas las aplicaciones.
**Desventajas**: Requiere admin, IPs estaticas (pueden cambiar), no soporta wildcards.

### 2. DNS-over-HTTPS / DoH (usado en este proyecto)

En lugar de consultar al DNS del router (UDP puerto 53, texto plano), se hace una consulta HTTPS a un servicio como Google DNS:

```
GET https://dns.google/resolve?name=store.steampowered.com&type=A
```

La respuesta viene encriptada por HTTPS, el router no puede ver ni modificar la consulta.

**Implementacion en el proxy**:
```javascript
function dohResolve(hostname) {
  const url = `https://dns.google/resolve?name=${hostname}&type=A`;
  // Hace HTTPS request a Google DNS
  // Parsea la respuesta JSON
  // Retorna la IP real
}
```

**Ventajas**: Encriptado, el router no puede ver la consulta, dinamico.
**Desventajas**: Requiere conexion HTTPS a dns.google, agrega latencia (~200ms por consulta).

### 3. Cambiar DNS del sistema (no usado)

Configurar Windows para usar DNS over HTTPS nativamente (Windows 11):
- Configuracion > Red > DNS > Usar DoH con 8.8.8.8 o 1.1.1.1

**No funciono aqui** porque el router podria forzar todo el trafico DNS a traves de si mismo (DNS hijacking).

### 4. DNS local personalizado (no usado)

Ejecutar un servidor DNS local (como `dnsmasq` o `CoreDNS`) que resuelve dominios Steam via DoH y el resto normalmente.

**No fue necesario** porque la combinacion de hosts + proxy DoH cubrio todas las necesidades.

## Dominios Steam que requieren bypass DNS

### Servicios principales
| Dominio | IP Real | Funcion |
|---|---|---|
| store.steampowered.com | 23.57.121.205 | Tienda |
| api.steampowered.com | 96.6.206.56 | API REST |
| steamcommunity.com | 96.6.206.56 | Comunidad |
| login.steampowered.com | 96.6.206.56 | Autenticacion |
| help.steampowered.com | 96.6.206.56 | Soporte |

### Client Manager (CM)
| Dominio | IP Real | Funcion |
|---|---|---|
| cm0.steampowered.com | 162.254.195.44 | Amigos, chat, presencia |
| ext1-lim1.steamserver.net | 155.133.244.34 | CM Lima |
| ext2-lim1.steamserver.net | 155.133.244.50 | CM Lima |

### CDN / Contenido estatico
| Dominio | IP Real | Funcion |
|---|---|---|
| cdn.cloudflare.steamstatic.com | 200.60.190.10 | Assets via Cloudflare |
| cdn.akamai.steamstatic.com | 200.60.190.10 | Assets via Akamai |
| avatars.steamstatic.com | 199.232.211.52 | Avatares (Fastly CDN) |
| store.cloudflare.steamstatic.com | 200.60.190.24 | Assets tienda |

### Servidores de descarga (Lima)
| Dominio | IP Real | Funcion |
|---|---|---|
| lancache.steamcontent.com | 155.133.244.4 | Deteccion LANCache |
| cache1-lim1.steamcontent.com | 155.133.244.4 | Descarga cache 1 |
| cache2-lim1.steamcontent.com | 155.133.244.20 | Descarga cache 2 |
| cache3-lim1.steamcontent.com | 155.133.244.3 | Descarga cache 3 |
| cache4-lim1.steamcontent.com | 155.133.244.19 | Descarga cache 4 |

## Infraestructura DNS de Steam

Steam utiliza una infraestructura DNS compleja con multiples capas de CNAME:

```
lancache.steamcontent.com
  → origin-tier2.steampipe.steamcontent.com       (Valve)
    → steampipe-origin-tier2.steamcontent.com      (Valve)
      → cache-origin.steampipe.steamcontent.akadns.net  (Akamai DNS)
        → dist-eze1.discovery.steamserver.net       (Valve Discovery)
          → 155.133.255.9                            (IP final)
```

Esta cadena permite a Valve redirigir el trafico globalmente usando Akamai como sistema de discovery geografico. El sufijo del discovery server indica la region:
- `ams1` = Amsterdam
- `eze1` = Buenos Aires (Ezeiza)
- `lim1` = Lima
- `scl1` = Santiago de Chile
- `atl3` = Atlanta
