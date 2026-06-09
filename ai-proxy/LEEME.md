# AI Proxy — ChatGPT y Claude en redes bloqueadas

Elude el bloqueo DNS + DPI/SNI que aplican firewalls corporativos (Checkpoint, PaloAlto NGFW).

---

## Requisito unico

**Node.js 18 o superior** — https://nodejs.org/ (opcion "LTS")

Verificar en PowerShell:
```
node --version
```
Si devuelve algo como `v20.11.0`, ya esta.

---

## Uso — doble click en START.bat

```
START.bat  <—  doble click aqui
```

**La primera vez** descarga Electron automaticamente (1-2 minutos, necesita internet).
Las siguientes veces abre instantaneo.

Si Windows muestra advertencia de seguridad, elegir "Ejecutar de todas formas".

---

## La ventana de control

Al abrir, el proxy arranca solo y aparece un icono en la bandeja del sistema (junto al reloj).

- **Boton Iniciar / Detener** — controla el proxy
- **Tab Logs** — conexiones en tiempo real
- **Tab Dominios** — dominios con IPs y cantidad de conexiones
- **Click en el icono de la bandeja** — muestra u oculta la ventana

Cerrar con X oculta la ventana — el proxy **sigue corriendo**.
Para salir: click derecho en el icono → **Salir**.

---

## Proxy del sistema

Cuando el proxy esta activo, configura automaticamente el proxy de Windows.
Al detenerlo restaura la configuracion anterior.

Funciona directo para **Edge y Chrome** (modo proxy del sistema).

**Firefox** — configurar manualmente una vez:
1. Menu ≡ → Configuracion → buscar "proxy" → Configuracion de red
2. Marcar **Configuracion manual del proxy**
3. Proxy HTTP: `127.0.0.1`  Puerto: `8889`
4. Tildar **Usar este proxy para HTTPS tambien**
5. Aceptar

---

## Archivos incluidos

| Archivo | Para que |
|---|---|
| `START.bat` | **Doble click para abrir** |
| `package.json` | Dependencias (Electron) |
| `main.js` | Logica principal de la app |
| `index.html` | Interfaz de la ventana |
| `ai-proxy.js` | El proxy (no modificar) |
| `test-ips.js` | Diagnostico de IPs disponibles |
| `test-tls.js` | Diagnostico TLS |

---

## Diagnostico rapido

```powershell
node test-ips.js
```

| Sintoma | Causa probable |
|---|---|
| `ECONNRESET` en los logs | DPI detecta el SNI — fragmentacion TLS activa por defecto |
| `ECONNRESET` persistente | IP bloqueada — ejecutar `test-ips.js` |
| `TIMEOUT` | Aumentar delays en `ai-proxy.js` (lineas ~212 y ~217) |

---

## Preguntas frecuentes

**¿Afecta Teams, Outlook?**
Normalmente no. Si hay problemas, detener el proxy y verificar.

**¿Funciona en Mac/Linux?**
Si. Ejecutar directamente:
```bash
npm install
npx electron .
```

**¿Las IPs pueden quedar desactualizadas?**
Si. Si deja de funcionar, ejecutar `node test-ips.js`.
