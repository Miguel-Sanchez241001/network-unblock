# Steam Proxy - Resumen General del Proyecto

## Que es este proyecto

Un sistema de bypass para evadir el bloqueo de Steam implementado a nivel de red corporativa/ISP. El bloqueo operaba en **tres capas** simultaneas, y fue necesario derrotar cada una para lograr que Steam funcionara completamente (navegacion, amigos, chat Y descargas de juegos).

## El escenario

- **Red corporativa** con seguridad avanzada: Checkpoint Endpoint Security + Palo Alto Networks
- **Router/Firewall** bloqueando Steam mediante:
  1. **Bloqueo DNS**: El router intercepta consultas DNS para dominios `*steam*` y devuelve IPs falsas
  2. **Inspeccion DPI/SNI**: El firewall inspecciona el campo SNI dentro del TLS ClientHello para bloquear conexiones HTTPS a dominios Steam
  3. **Redireccion LANCache**: El DNS del router devuelve `10.0.0.2` para `lancache.steamcontent.com`, engañando a Steam para que descargue de una maquina local inexistente

## La solucion (3 componentes)

### 1. Proxy HTTP con resolucion DNS alternativa (`steam-proxy.js`)
- Proxy HTTP/CONNECT en `127.0.0.1:8888`
- Resuelve dominios Steam via **DNS-over-HTTPS** (dns.google) en lugar del DNS del router
- Auto-detecta dominios Steam no mapeados y los resuelve dinamicamente

### 2. TLS Record Splitting (bypass DPI/SNI)
- Divide el TLS ClientHello en **dos registros TLS separados** a nivel de protocolo
- El primer registro contiene el inicio del handshake (sin SNI)
- El segundo registro contiene el SNI y el resto
- El DPI no puede reensamblar porque necesitaria parsear el protocolo TLS completo

### 3. Hosts file fix (bypass LANCache falso)
- Agrega IPs reales de Valve al archivo hosts del sistema
- Evita que el DNS del router redirija `lancache.steamcontent.com` a `10.0.0.2`
- Steam deja de pensar que hay un cache local y usa los servidores reales

## Archivos del proyecto

```
C:\workspace\proxy\
├── steam-proxy.js          # Proxy principal (DNS bypass + TLS Record Splitting)
├── fix-lancache.bat        # Script para corregir hosts file (LANCache)
├── setup-hosts.bat         # Script alternativo para hosts file completo
├── remove-hosts.bat        # Script para remover entradas del hosts file
├── analisis.txt            # Captura Wireshark original (analisis inicial)
└── docs/
    ├── 01-RESUMEN-GENERAL.md        # Este archivo
    ├── 02-ANALISIS-RED.md           # Analisis de la captura de red
    ├── 03-BLOQUEO-DNS.md            # Como funciona el bloqueo DNS y su bypass
    ├── 04-DPI-SNI-BLOCKING.md       # Deep Packet Inspection y bypass SNI
    ├── 05-TLS-RECORD-SPLITTING.md   # La tecnica clave de fragmentacion TLS
    ├── 06-LANCACHE-PROBLEM.md       # El problema del LANCache falso
    ├── 07-PROXY-ARQUITECTURA.md     # Arquitectura del proxy
    ├── 08-PROCESO-DESARROLLO.md     # Cronologia del proceso de desarrollo
    └── 09-GLOSARIO.md               # Glosario de todos los terminos tecnicos
```

## Resultado final

| Funcionalidad | Estado | Metodo de bypass |
|---|---|---|
| Steam Store (tienda) | Funciona | Proxy + TLS Record Splitting |
| Steam Community (amigos) | Funciona | Proxy + TLS Record Splitting |
| Steam API (login, datos) | Funciona | Proxy + TLS Record Splitting |
| Steam CM (chat, presencia) | Funciona | Proxy + DoH |
| Descarga de juegos (Dota 2) | Funciona | Hosts file + Proxy + TLS Record Splitting |
