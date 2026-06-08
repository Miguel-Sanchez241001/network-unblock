# Glosario de Terminos Tecnicos

## Redes y Protocolos

| Termino | Definicion |
|---|---|
| **TCP** (Transmission Control Protocol) | Protocolo de transporte (capa 4 OSI) que garantiza entrega ordenada y confiable de datos. Todas las conexiones del proxy operan sobre TCP. |
| **UDP** (User Datagram Protocol) | Protocolo de transporte sin conexion. Steam CM lo usa por defecto; el flag `-tcp` lo fuerza a TCP para que pase por el proxy. |
| **IP** (Internet Protocol) | Protocolo de capa 3 que enruta paquetes entre redes usando direcciones numericas (ej: 155.133.244.4). |
| **IPv4** | Version 4 del protocolo IP. Direcciones de 32 bits escritas como 4 octetos decimales (ej: `23.57.121.205`). |
| **IPv6** | Version 6 del protocolo IP. Direcciones de 128 bits. Steam usa notacion IPv4-mapped como `::ffff:a00:2` que representa `10.0.0.2`. |
| **HTTP** (HyperText Transfer Protocol) | Protocolo de aplicacion para transferir documentos web. Puerto 80. Sin encriptacion. |
| **HTTPS** (HTTP Secure) | HTTP encriptado mediante TLS. Puerto 443. Usado por Steam para tienda, API y descargas. |
| **HTTP/2** | Version moderna de HTTP con multiplexion de streams. Steam lo usa para descargas de chunks dentro del tunel TLS. |
| **Paquete** | Unidad de datos transmitida por la red. Contiene headers (IP, TCP) y payload (datos de aplicacion). |
| **Payload** | Contenido util de un paquete, excluyendo los headers. En un TLS Record, el payload es el mensaje de handshake. |
| **Segmento TCP** | Unidad de datos a nivel de capa de transporte. Un paquete TCP puede dividirse en multiples segmentos. |
| **Puerto** | Numero (0-65535) que identifica un servicio en una maquina. Puerto 80 = HTTP, 443 = HTTPS, 53 = DNS, 8888 = nuestro proxy. |
| **Socket** | Punto final de comunicacion de red. Representa una conexion TCP con operaciones como `write()`, `read()`, `connect()`. |
| **Gateway** | Dispositivo que conecta dos redes. El router actua como gateway entre la red local e internet. En VPN: 10.2.0.1. |
| **Subnet** | Rango de direcciones IP dentro de una red. Valve Lima opera en la subnet `155.133.244.x`. |
| **IP Publica** | Direccion IP accesible desde internet (ej: `155.133.244.4`). |
| **IP Privada** | Direccion IP solo accesible dentro de una red local (ej: `10.0.0.2`, `192.168.x.x`). No ruteables en internet. |

## DNS (Domain Name System)

| Termino | Definicion |
|---|---|
| **DNS** | Sistema que traduce nombres de dominio legibles (`store.steampowered.com`) a direcciones IP (`23.57.121.205`). |
| **Consulta DNS** (DNS Query) | Peticion para resolver un nombre de dominio a su IP. Normalmente se envia por UDP puerto 53 al router/servidor DNS. |
| **Registro A** (A Record) | Tipo de registro DNS que mapea un dominio directamente a una direccion IPv4. |
| **CNAME** (Canonical Name) | Tipo de registro DNS que crea un alias de un dominio a otro. Steam encadena multiples CNAMEs para enrutamiento geografico. |
| **NXDOMAIN** | Respuesta DNS que indica que el dominio no existe. El router la devuelve para bloquear dominios Steam. |
| **DNS Hijacking** | Tecnica donde el router intercepta consultas DNS y devuelve respuestas falsas o nulas, impidiendo la resolucion correcta. |
| **DoH** (DNS-over-HTTPS) | Protocolo que encapsula consultas DNS dentro de peticiones HTTPS. Impide que el router vea o modifique la consulta DNS. API usada: `https://dns.google/resolve`. |
| **Archivo Hosts** | Archivo del sistema (`C:\Windows\System32\drivers\etc\hosts` en Windows) que mapea dominios a IPs. Se consulta ANTES que cualquier servidor DNS. |
| **TTL** (Time To Live) | Tiempo en segundos que un registro DNS puede ser cacheado antes de requerir una nueva consulta. |

## TLS (Transport Layer Security)

| Termino | Definicion |
|---|---|
| **TLS** | Protocolo criptografico que proporciona comunicacion segura (encriptada). Sucesor de SSL. Versiones relevantes: TLS 1.0, 1.2, 1.3. |
| **TLS Handshake** | Proceso de negociacion para establecer una conexion encriptada. Incluye intercambio de ClientHello, ServerHello, certificados y claves. |
| **ClientHello** | Primer mensaje del handshake TLS, enviado por el cliente. Contiene version, random, cipher suites, y extensiones (incluyendo SNI). Viaja **sin encriptar**. |
| **ServerHello** | Respuesta del servidor al ClientHello. Confirma la version TLS, cipher suite seleccionada, y envia su certificado. |
| **TLS Record** | Unidad de encapsulacion del protocolo TLS. Header de 5 bytes: Content Type (1B) + Version (2B) + Length (2B), seguido del payload. |
| **Content Type** | Primer byte del TLS Record. `0x16` = Handshake, `0x17` = Application Data, `0x15` = Alert. |
| **SNI** (Server Name Indication) | Extension TLS (tipo `0x0000`) dentro del ClientHello que indica el dominio destino en texto plano. Permite al servidor elegir que certificado presentar. Es el campo que el DPI inspecciona para bloquear. |
| **Extension TLS** | Campo adicional dentro del ClientHello que agrega funcionalidad. SNI, supported_versions, key_share son ejemplos. |
| **Cipher Suite** | Conjunto de algoritmos criptograficos (encriptacion, hash, intercambio de claves) que cliente y servidor negocian durante el handshake. |
| **Session ID** | Identificador de sesion TLS de longitud variable. Permite resumir sesiones previas sin repetir el handshake completo. |
| **Random** | 32 bytes aleatorios incluidos en el ClientHello, usados como entropia para derivar claves criptograficas. |
| **Certificado TLS** | Documento digital que vincula un dominio con una clave publica. El servidor lo envia durante el handshake para probar su identidad. |
| **ECH** (Encrypted Client Hello) | Extension de TLS 1.3 que encripta el SNI. Aun no ampliamente desplegada. Solucionaria el problema de SNI visible sin necesidad de proxy. |
| **RFC 8446** | Especificacion oficial de TLS 1.3. La Seccion 5.1 permite que un handshake message se fragmente en multiples TLS records — fundamento teorico del TLS Record Splitting. |

## TLS Record Splitting

| Termino | Definicion |
|---|---|
| **TLS Record Splitting** | Tecnica que divide un unico TLS Record (ClientHello) en dos registros TLS validos, cada uno con su propio header de 5 bytes. El primer registro no contiene el SNI; el segundo si. El DPI solo inspecciona el primero. |
| **Split Point** | Punto de corte dentro del payload del TLS Record. Se calcula 10 bytes antes de la posicion del SNI para asegurar que el nombre del servidor quede en el segundo registro. |
| **SNI Offset** | Posicion en bytes donde comienza el campo SNI dentro del ClientHello. Se calcula parseando la estructura del handshake: version, random, session ID, cipher suites, compression, extensions. |
| **Record 1** | Primer registro TLS resultante del splitting. Contiene el inicio del ClientHello (version, random, parte de ciphers). **No contiene SNI**. |
| **Record 2** | Segundo registro TLS. Contiene el resto del ClientHello incluyendo el SNI. Se envia 5ms despues del Record 1 como segmento TCP separado. |
| **Fragmentacion TCP** | Tecnica alternativa (que fallo) de dividir segmentos TCP. El DPI reensambla los segmentos TCP facilmente, anulando la fragmentacion. |

## DPI (Deep Packet Inspection)

| Termino | Definicion |
|---|---|
| **DPI** (Deep Packet Inspection) | Tecnica de inspeccion de red que analiza el contenido (payload) de los paquetes, no solo sus headers. Permite identificar y bloquear trafico por protocolo de aplicacion. |
| **Bloqueo por SNI** | Tipo especifico de DPI que busca el campo SNI dentro del TLS ClientHello. Si el SNI contiene un dominio bloqueado, descarta el paquete o envia un RST. |
| **TCP Reassembly** | Capacidad del DPI de reensamblar segmentos TCP fragmentados para reconstruir el stream original. Operacion basica de redes que anula la fragmentacion TCP simple. |
| **TLS Record Reassembly** | Capacidad avanzada de DPI (rara) de reensamblar multiples TLS records para reconstruir un handshake message completo. La mayoria de DPI NO implementa esto. |
| **RST** (Reset) | Paquete TCP que termina abruptamente una conexion. El firewall puede enviar un RST al detectar un SNI bloqueado. |
| **Domain Fronting** | Tecnica de bypass que usa un dominio permitido en el SNI pero uno diferente en el header HTTP Host. La mayoria de CDNs ya lo bloquean. |

## Proxy HTTP

| Termino | Definicion |
|---|---|
| **Proxy HTTP** | Servidor intermediario que recibe peticiones HTTP/HTTPS de clientes y las reenvía al destino. Opera en `127.0.0.1:8888`. |
| **CONNECT** | Metodo HTTP que solicita al proxy crear un tunel TCP transparente hacia el servidor destino. Usado para conexiones HTTPS. El proxy no ve el contenido encriptado. |
| **Tunel TCP** | Conexion TCP bidireccional creada por el metodo CONNECT. El proxy reenvía bytes en ambas direcciones sin interpretar el contenido. |
| **Header Host** | Header HTTP que indica el dominio destino. El proxy lo mantiene original (`Host: store.steampowered.com`) aunque conecte a la IP directa. |
| **Proxy del sistema** | Configuracion de Windows que redirige todo el trafico HTTP/HTTPS a traves del proxy. Configurado en Registro de Windows: `ProxyEnable=1`, `ProxyServer=127.0.0.1:8888`. |
| **502 Bad Gateway** | Codigo HTTP que indica que el proxy no pudo conectar al servidor destino. |
| **504 Gateway Timeout** | Codigo HTTP que indica que el servidor destino no respondio a tiempo. |

## Algoritmo de Nagle y TCP

| Termino | Definicion |
|---|---|
| **Algoritmo de Nagle** | Optimizacion TCP que agrupa escrituras pequeñas consecutivas en un solo segmento para reducir overhead. Activado por defecto. |
| **TCP_NODELAY** / `setNoDelay(true)` | Opcion de socket que desactiva el algoritmo de Nagle. Cada `socket.write()` se envia como un segmento TCP independiente. **Esencial** para que los dos TLS Records se envien como paquetes separados. |

## Steam y Valve

| Termino | Definicion |
|---|---|
| **Steam** | Plataforma de distribucion digital de videojuegos desarrollada por Valve. |
| **Valve** | Empresa desarrolladora de videojuegos y propietaria de Steam. Opera la infraestructura de servidores. |
| **Steam CM** (Client Manager) | Servidores de control de Steam que manejan autenticacion, lista de amigos, chat y presencia online. No son servidores de descarga. |
| **SteamPipe** | Sistema de distribucion de contenido de Steam. Divide los archivos en chunks de ~1MB, los comprime, encripta, y los distribuye via CDN HTTP/HTTPS. |
| **Depot** | Unidad de almacenamiento de contenido en Steam. Cada juego tiene uno o mas depots identificados por `depot_id` (ej: 373301 para Dota 2). |
| **Chunk** | Fragmento de ~1MB de un archivo de juego. Identificado por un hash unico. URL de descarga: `/depot/{depot_id}/chunk/{chunk_hash}`. |
| **CellID** | Identificador numerico de region geografica en Steam. Determina que servidores de descarga se asignan. CellID 118 = Lima, CellID 12 = Atlanta. |
| **LANCache** | Sistema de cache de red local. Si `lancache.steamcontent.com` resuelve a una IP privada, Steam cambia a HTTP puerto 80 y descarga del cache local, saltando el proxy. |
| **proxyconfig.vdf** | Archivo de configuracion de proxy de Steam. `proxy_mode=2` habilita proxy manual. Ubicacion: `C:\Program Files (x86)\Steam\config\`. |
| **content_log.txt** | Log de descargas de Steam. Registra activaciones de LANCache, errores de chunks, y velocidades. Ubicacion: `C:\Program Files (x86)\Steam\logs\`. |
| **Flag `-tcp`** | Parametro de linea de comandos de Steam que fuerza conexiones TCP (en vez de UDP) para servidores CM, asegurando que pasen por el proxy CONNECT. |

## CDN (Content Delivery Networks)

| Termino | Definicion |
|---|---|
| **CDN** (Content Delivery Network) | Red de servidores distribuidos que entregan contenido desde ubicaciones geograficamente cercanas al usuario. |
| **Akamai** | Proveedor CDN global. Maneja DNS (`akadns.net`) y contenido estatico de Steam. |
| **Cloudflare** | Proveedor CDN. Sirve assets de la tienda y comunidad Steam (`cdn.cloudflare.steamstatic.com`). |
| **Fastly** | Proveedor CDN. Sirve avatares de Steam (`avatars.steamstatic.com`, IP: 199.232.211.52). |
| **Discovery Server** | Servidor de Valve que resuelve la region del usuario. Sufijo indica ubicacion: `lim1` = Lima, `eze1` = Buenos Aires, `ams1` = Amsterdam. |

## Regiones de Servidores Steam

| Codigo | Ubicacion | Ejemplo |
|---|---|---|
| **LIM1** | Lima, Peru | `cache1-lim1.steamcontent.com`, `ext1-lim1.steamserver.net` |
| **EZE1** | Buenos Aires, Argentina (Ezeiza) | `dist-eze1.discovery.steamserver.net` |
| **SCL1** | Santiago, Chile | Servidores regionales sudamericanos |
| **AMS1** | Amsterdam, Holanda | Servidores europeos |
| **ATL3** | Atlanta, EEUU | Servidores norteamericanos (VPN US) |
| **VIE1** | Viena, Austria | `p2p-vie1.discovery.steamserver.net` |

## Seguridad Corporativa

| Termino | Definicion |
|---|---|
| **Checkpoint Endpoint Security** | Software de seguridad corporativa instalado en endpoints. Parte de la infraestructura de bloqueo en este escenario. |
| **Palo Alto Networks** | Fabricante de firewalls de siguiente generacion con capacidades DPI avanzadas. |
| **Firewall** | Dispositivo de seguridad de red que controla el trafico entrante y saliente basandose en reglas predefinidas. |
| **VPN** (Virtual Private Network) | Tunel encriptado que enruta todo el trafico a traves de un servidor remoto, ocultandolo del router local. ProtonVPN fue la solucion original antes de alcanzar su limite. |

## Herramientas Utilizadas

| Termino | Definicion |
|---|---|
| **Wireshark** | Analizador de protocolos de red. Captura y muestra paquetes en detalle. Se uso para analizar el trafico Steam via ProtonVPN. |
| **curl** | Herramienta de linea de comandos para transferir datos via HTTP/HTTPS. Se uso para probar el bloqueo SNI con `--resolve` y `-k`. |
| **Node.js** | Entorno de ejecucion de JavaScript del lado del servidor. Lenguaje en el que esta escrito `steam-proxy.js`. |
| **nslookup / dig** | Herramientas de linea de comandos para consultar registros DNS. |
| **PowerShell** | Shell de Windows usado para verificar configuracion de proxy y ejecutar scripts de limpieza. |

## Codigos de Error

| Codigo | Significado |
|---|---|
| **HTTP 200** | Conexion exitosa. |
| **HTTP 302** | Redireccion. Observado en pruebas iniciales de HTTP a Steam Store. |
| **HTTP 400** | Bad Request. El servidor responde pero no sabe que dominio servir (sin SNI). Prueba de que la IP es alcanzable. |
| **TIMEOUT** | La conexion no se establece en el tiempo limite. Indica bloqueo DPI activo. |
| **ENOTFOUND** | Error de Node.js: el DNS del sistema no puede resolver el dominio. |
| **ECONNRESET** | Error de Node.js: el servidor remoto cerro la conexion abruptamente. |
| **EADDRINUSE** | Error de Node.js: el puerto ya esta en uso por otro proceso. |
| **Unpack failed (c:240,u:0,r:243,b:243)** | Error de Steam al desempaquetar un chunk corrupto. Sintoma del LANCache falso: el chunk descargado de `10.0.0.2` estaba vacio o invalido. |

## Valores de Bytes del Protocolo TLS

| Valor | Significado |
|---|---|
| **0x16** | Content Type: Handshake. Indica que el TLS Record contiene un mensaje de handshake. |
| **0x17** | Content Type: Application Data. Datos encriptados de la aplicacion. |
| **0x15** | Content Type: Alert. Mensajes de error o cierre TLS. |
| **0x0301** | Version TLS 1.0. Usado en el header del ClientHello por compatibilidad (legacy). |
| **0x0303** | Version TLS 1.2. |
| **0x0000** | Extension Type: SNI (Server Name Indication). |
| **0x01** | Handshake Type: ClientHello. |
| **0x02** | Handshake Type: ServerHello. |
