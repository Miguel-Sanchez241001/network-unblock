# TLS Record Splitting - La Tecnica Clave

## Fundamento teorico

La especificacion TLS (RFC 8446, Seccion 5.1) establece:

> "Multiple handshake messages MAY be coalesced into a single TLS record,
> and a single handshake message MAY be fragmented over several TLS records."

Esto significa que un ClientHello puede dividirse en multiples registros TLS y el servidor DEBE reensamblarlo. Los sistemas DPI, sin embargo, generalmente NO implementan este reensamblaje.

## Estructura de un TLS Record

```
┌──────────────────────────────────────┐
│ Content Type   (1 byte)  = 0x16      │  ← Handshake
│ Protocol Version (2 bytes) = 0x0301  │  ← TLS 1.0 (en ClientHello)
│ Length          (2 bytes)  = N        │  ← Longitud del payload
├──────────────────────────────────────┤
│ Payload         (N bytes)            │  ← Datos del handshake
└──────────────────────────────────────┘
```

Total: 5 bytes de header + N bytes de payload.

## Como funciona el splitting

### Antes (un solo registro TLS)

```
[TLS Record: type=0x16, ver=0x0301, len=512]
[ClientHello: version, random, session_id, ciphers, extensions[SNI="steampowered.com", ...]]
```

El DPI lee el registro completo, encuentra el SNI, bloquea.

### Despues (dos registros TLS)

```
[TLS Record 1: type=0x16, ver=0x0301, len=130]
[Primeros 130 bytes del ClientHello: version, random, session_id, ciphers_parcial...]

--- TCP segment boundary (paquete separado) ---

[TLS Record 2: type=0x16, ver=0x0301, len=382]
[Resto del ClientHello: ...ciphers_resto, extensions[SNI="steampowered.com", ...]]
```

El DPI ve el Record 1: no contiene SNI (es solo el inicio del ClientHello). La mayoria de DPI no esperan un segundo registro con la continuacion del mismo handshake message.

## Implementacion en el codigo

### Paso 1: Parsear el TLS Record original

```javascript
// Verificar que es un TLS Handshake
if (data[0] !== 0x16) return; // No es handshake

const version = (data[1] << 8) | data[2];   // Version del protocolo
const recordLen = (data[3] << 8) | data[4]; // Longitud del payload
const payload = data.slice(5, 5 + recordLen);
```

### Paso 2: Encontrar el SNI dentro del ClientHello

```javascript
// El ClientHello tiene esta estructura dentro del payload:
// [1B handshake_type] [3B length] [2B version] [32B random]
// [1B+N session_id] [2B+N ciphers] [1B+N compression]
// [2B extensions_length] [extensions...]

// Iterar por las extensions buscando type 0x0000 (SNI)
while (offset < extEnd) {
    const extType = (data[offset] << 8) | data[offset + 1];
    const extLen = (data[offset + 2] << 8) | data[offset + 3];
    if (extType === 0x0000) {
        // SNI encontrado!
        // offset + 9 = inicio del nombre del servidor
        return offset + 9 + Math.floor(nameLen / 2);
    }
    offset += 4 + extLen;
}
```

### Paso 3: Construir dos TLS Records

```javascript
// Punto de corte: justo antes del SNI
const splitPoint = sniOffset - 5 - 10; // 10 bytes antes del SNI

const part1 = payload.slice(0, splitPoint);
const part2 = payload.slice(splitPoint);

// Record 1: header + primera parte
const record1 = Buffer.alloc(5 + part1.length);
record1[0] = 0x16;                              // Content Type: Handshake
record1.writeUInt16BE(version, 1);               // Version
record1.writeUInt16BE(part1.length, 3);           // Length de part1
part1.copy(record1, 5);

// Record 2: header + segunda parte (contiene SNI)
const record2 = Buffer.alloc(5 + part2.length);
record2[0] = 0x16;
record2.writeUInt16BE(version, 1);
record2.writeUInt16BE(part2.length, 3);
part2.copy(record2, 5);
```

### Paso 4: Enviar como TCP segments separados

```javascript
socket.setNoDelay(true);    // Desactivar Nagle
socket.write(record1);       // → TCP segment 1
setTimeout(() => {
    socket.write(record2);   // → TCP segment 2 (5ms despues)
    socket.setNoDelay(false); // Restaurar Nagle
}, 5);
```

## Por que funciona

### Comparacion: Fragmentacion TCP vs TLS Record Splitting

| Aspecto | Fragmentacion TCP | TLS Record Splitting |
|---|---|---|
| Nivel | Transporte (capa 4) | Aplicacion/TLS (capa 6-7) |
| Que divide | Segmentos TCP | Registros TLS |
| DPI reassembly | Facil (TCP reassembly estandar) | Dificil (requiere parsear TLS) |
| Validez del paquete | Cada segmento es TCP valido | Cada registro es TLS valido |
| Soporte servidor | Automatico (TCP stack) | Requerido por RFC 8446 |
| Efectividad contra DPI | Baja-Media | Alta |

### La razon tecnica

1. El DPI realiza **TCP reassembly** automaticamente — es una funcion basica de cualquier sistema de red. Los segmentos TCP se reensamblan en el stream original, anulando la fragmentacion.

2. Para **TLS record reassembly**, el DPI necesitaria:
   - Parsear el header de cada TLS record (5 bytes)
   - Entender que un Handshake message puede abarcar multiples records
   - Mantener estado entre records para reensamblar el ClientHello
   - LUEGO parsear el ClientHello reensamblado para extraer el SNI

3. La mayoria de DPI (incluyendo el de este escenario con Checkpoint/Palo Alto) implementan un parser simple: leen el primer TLS record, buscan el SNI ahi, y si no lo encuentran, pasan el trafico.

## Ejemplo real del log del proxy

```
[STEAM] CONNECT cache1-lim1.steamcontent.com:443 -> 155.133.244.4:443
[FRAG] TLS ClientHello cache1-lim1.steamcontent.com (455 bytes)
  TLS Record Split: SNI@145, split payload@130
  -> Record1: 135B (header+130) | Record2: 325B (header+320)
```

- ClientHello original: 455 bytes (5 header + 450 payload)
- SNI encontrado en offset absoluto 145 (offset 140 dentro del payload)
- Corte en payload offset 130 (10 bytes antes del SNI)
- Record 1: 135 bytes (5 header + 130 payload) — NO contiene SNI
- Record 2: 325 bytes (5 header + 320 payload) — contiene el SNI
- DPI solo inspecciona Record 1 → no encuentra SNI → permite la conexion

## Evolucion de la solucion en este proyecto

### Version 1: Fragmentacion TCP simple (FALLO)
- Dividir en 3 segmentos TCP con delays de 2ms
- El DPI reensamblaba los segmentos → SNI visible → bloqueado

### Version 2: Fragmentacion TCP con setNoDelay (PARCIAL)
- Agregamos `socket.setNoDelay(true)` para forzar segmentos separados
- Primer corte muy temprano (byte 2) para romper el header TLS
- Funciono para algunos dominios, fallo para otros (el DPI aun reensamblaba)

### Version 3: TLS Record Splitting (EXITO)
- Division a nivel de protocolo TLS, no TCP
- Dos registros TLS validos, cada uno con su propio header de 5 bytes
- El DPI no puede reensamblar sin implementar un parser TLS completo
- 100% efectivo en este escenario
