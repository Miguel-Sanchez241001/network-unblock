'use strict'
const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, dialog } = require('electron')
const { spawn, execFileSync } = require('child_process')
const path = require('path')
const zlib = require('zlib')
const net  = require('net')
const os   = require('os')
const fs   = require('fs')

app.setName('AI Proxy')
if (process.platform === 'win32') app.setAppUserModelId('com.aiproxy.app')

// ── Generar icono PNG en memoria (sin archivos externos) ─────
function crc32(buf) {
  let c = 0xFFFFFFFF
  for (const b of buf) {
    c ^= b
    for (let i = 0; i < 8; i++) c = c & 1 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1
  }
  return (c ^ 0xFFFFFFFF) >>> 0
}
function pngChunk(type, data) {
  const out = Buffer.alloc(12 + data.length)
  out.writeUInt32BE(data.length, 0)
  out.write(type, 4)
  data.copy(out, 8)
  out.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type), data])), 8 + data.length)
  return out
}
function makeCirclePNG(sz, r, g, b) {
  const cx = sz / 2, rad = sz / 2 - 1.5
  const rows = []
  for (let y = 0; y < sz; y++) {
    rows.push(0)
    for (let x = 0; x < sz; x++) {
      const d = Math.hypot(x - cx + 0.5, y - cx + 0.5)
      const a = d < rad ? 255 : d < rad + 1.5 ? Math.round((rad + 1.5 - d) / 1.5 * 255) : 0
      rows.push(r, g, b, a)
    }
  }
  const idat = zlib.deflateSync(Buffer.from(rows))
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(sz, 0); ihdr.writeUInt32BE(sz, 4)
  ihdr[8] = 8; ihdr[9] = 6
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', idat),
    pngChunk('IEND', Buffer.alloc(0))
  ])
}
const IMG_ON  = nativeImage.createFromBuffer(makeCirclePNG(22, 50, 210, 50))
const IMG_OFF = nativeImage.createFromBuffer(makeCirclePNG(22, 110, 110, 110))

// ── Proxy del sistema (registro Windows) ────────────────────
const REG = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings'
let savedProxy = { enable: '0', server: '' }

const REG_OPTS = { windowsHide: true }
function regAdd(name, type, value) {
  execFileSync('reg', ['add', REG, '/v', name, '/t', type, '/d', value, '/f'], REG_OPTS)
}

function saveCurrentProxy() {
  try {
    const out = execFileSync('reg', ['query', REG], REG_OPTS).toString()
    const en  = out.match(/ProxyEnable\s+REG_DWORD\s+(0x\w+)/)
    const sv  = out.match(/ProxyServer\s+REG_SZ\s+(\S+)/)
    savedProxy.enable = en ? parseInt(en[1], 16).toString() : '0'
    savedProxy.server = sv ? sv[1].trim() : ''
  } catch { savedProxy = { enable: '0', server: '' } }
}

function setSystemProxy(enable) {
  if (process.platform !== 'win32') return
  try {
    if (enable) {
      saveCurrentProxy()
      regAdd('ProxyEnable',   'REG_DWORD', '1')
      regAdd('ProxyServer',   'REG_SZ',    '127.0.0.1:8889')
      regAdd('ProxyOverride', 'REG_SZ',    '<local>;localhost;127.0.0.1')
    } else {
      regAdd('ProxyEnable', 'REG_DWORD', savedProxy.enable)
      if (savedProxy.server) regAdd('ProxyServer', 'REG_SZ', savedProxy.server)
    }
  } catch { /* no bloquear la UI por error de registro */ }
}

// ── Estado global ────────────────────────────────────────────
let win       = null
let tray      = null
let proxyProc = null
let domains   = {}

// ── Ventana ──────────────────────────────────────────────────
function createWindow() {
  win = new BrowserWindow({
    width: 540, height: 600,
    minWidth: 380, minHeight: 380,
    backgroundColor: '#1c1c1c',
    title: 'AI Proxy',
    icon: IMG_OFF,
    webPreferences: { nodeIntegration: true, contextIsolation: false }
  })
  win.loadFile('index.html')
  win.setMenu(null)
  win.on('close', e => { e.preventDefault(); win.hide() })
}

// ── Tray ─────────────────────────────────────────────────────
function createTray() {
  tray = new Tray(IMG_OFF)
  tray.setToolTip('AI Proxy — Inactivo')
  refreshTrayMenu()
  tray.on('click', () => win.isVisible() ? win.hide() : (win.show(), win.focus()))
}

function refreshTrayMenu() {
  const active = isActive()
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: active ? 'Detener proxy' : 'Iniciar proxy', click: toggleProxy },
    { label: 'Abrir ventana',                            click: () => { win.show(); win.focus() } },
    { type: 'separator' },
    { label: 'Salir',                                    click: () => app.quit() }
  ]))
}

// ── Puerto en uso ────────────────────────────────────────────
function isPortInUse(port) {
  return new Promise(resolve => {
    const s = net.createServer()
    s.once('error', e => resolve(e.code === 'EADDRINUSE'))
    s.once('listening', () => { s.close(); resolve(false) })
    s.listen(port, '127.0.0.1')
  })
}

function getPidOnPort(port) {
  try {
    const out = execFileSync('netstat', ['-ano'], { windowsHide: true }).toString()
    for (const line of out.split('\n')) {
      if (line.includes(`:${port}`) && line.toUpperCase().includes('LISTENING')) {
        const pid = parseInt(line.trim().split(/\s+/).pop())
        if (!isNaN(pid) && pid > 0) return pid
      }
    }
  } catch {}
  return null
}

// ── Proxy ────────────────────────────────────────────────────
function isActive() { return !!(proxyProc && !proxyProc.killed) }

async function startProxy() {
  if (isActive()) return

  if (await isPortInUse(8889)) {
    const pid = getPidOnPort(8889)
    const pidLabel = pid ? ` (PID ${pid})` : ''
    const choice = dialog.showMessageBoxSync(win, {
      type: 'warning',
      title: 'Puerto 8889 ocupado',
      message: `Hay otro proceso usando el puerto 8889${pidLabel}.`,
      detail: '¿Querés terminar ese proceso y liberar el puerto?',
      buttons: ['Terminar proceso', 'Cancelar'],
      defaultId: 0,
      cancelId: 1
    })
    if (choice !== 0) return
    if (pid) {
      try { process.kill(pid) } catch {}
      await new Promise(r => setTimeout(r, 600))
    }
  }

  const proxyPath = path.join(__dirname, 'ai-proxy.js')
  proxyProc = spawn('node', [proxyPath], { windowsHide: true, env: { ...process.env } })
  proxyProc.stdout.on('data', d => handleLog(d.toString()))
  proxyProc.stderr.on('data', d => handleLog(d.toString(), true))
  proxyProc.on('exit', () => {
    proxyProc = null
    setSystemProxy(false)
    syncUI(false)
    sendLog('-- Proceso de proxy terminado --', 'error')
  })
  setSystemProxy(true)
  syncUI(true)
  sendLog('-- Proxy iniciado | sistema configurado en 127.0.0.1:8889 --', 'ok')
}

function stopProxy() {
  if (proxyProc) { try { proxyProc.kill('SIGTERM') } catch {} proxyProc = null }
  setSystemProxy(false)
  domains = {}
  syncUI(false)
  sendLog('-- Proxy detenido | configuracion del sistema restaurada --', 'warn')
}

function toggleProxy() { isActive() ? stopProxy() : startProxy() }

function syncUI(active) {
  tray.setImage(active ? IMG_ON : IMG_OFF)
  tray.setToolTip(active ? 'AI Proxy - Activo (8889)' : 'AI Proxy - Inactivo')
  refreshTrayMenu()
  if (win && !win.isDestroyed())
    win.webContents.send('state', { active, domains: domainsArray() })
}

function domainsArray() {
  return Object.entries(domains).map(([host, d]) => ({ host, ...d }))
}

// ── Procesar lineas del proxy ────────────────────────────────
function handleLog(text, isErr = false) {
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const clean = line.replace(/\x1b\[[0-9;]*m/g, '')

    // Actualizar tabla de dominios
    const m = clean.match(/\[(AI|PASS)\]\s+CONNECT\s+([^\s:]+):\d+\s+->\s+([^:\s]+):/)
    if (m) {
      const [, type, host, ip] = m
      if (!domains[host]) domains[host] = { ip, count: 0, type }
      domains[host].count++
      domains[host].ip = ip
      if (win && !win.isDestroyed())
        win.webContents.send('domains', domainsArray())
    }

    const type = isErr ? 'error' : classify(clean)
    sendLog(clean, type)
  }
}

function classify(line) {
  if (/\[AI\]/.test(line))      return 'ai'
  if (/\[PASS\]/.test(line))    return 'pass'
  if (/\[FRAG\]/.test(line))    return 'frag'
  if (/\[ERROR\]/.test(line))   return 'error'
  if (/\[TIMEOUT\]/.test(line)) return 'timeout'
  if (/\[DoH\]/.test(line))     return 'doh'
  return 'info'
}

function sendLog(text, type) {
  if (!win || win.isDestroyed()) return
  const ts = new Date().toLocaleTimeString('es', { hour12: false })
  win.webContents.send('log', { text, type, ts })
}

// ── IPC ──────────────────────────────────────────────────────
ipcMain.on('toggle',     () => toggleProxy())
ipcMain.on('clear-logs', () => { domains = {}; if (win) win.webContents.send('clear') })
ipcMain.handle('get-state', () => ({ active: isActive(), domains: domainsArray() }))

// ── Acceso directo en escritorio ─────────────────────────────
// Siempre sobreescribe para que apunte a la ubicacion actual del exe
function createDesktopShortcut() {
  if (process.platform !== 'win32') return
  const exe = path.join(__dirname, 'node_modules', 'electron', 'dist', 'electron.exe')
  if (!fs.existsSync(exe)) return
  const lnk = path.join(os.homedir(), 'Desktop', 'AI Proxy.lnk')
  const esc = s => s.replace(/\\/g, '\\\\').replace(/'/g, "''")
  const ps = [
    `$ws = New-Object -COM WScript.Shell`,
    `$s  = $ws.CreateShortcut('${esc(lnk)}')`,
    `$s.TargetPath       = '${esc(exe)}'`,
    `$s.Arguments        = '. --no-sandbox'`,
    `$s.WorkingDirectory = '${esc(__dirname)}'`,
    `$s.Description      = 'AI Proxy'`,
    `$s.Save()`
  ].join('; ')
  try { execFileSync('powershell', ['-WindowStyle', 'Hidden', '-Command', ps], { windowsHide: true }) } catch {}
}

// ── Ciclo de vida ────────────────────────────────────────────
app.whenReady().then(() => {
  createWindow()
  createTray()
  win.webContents.once('did-finish-load', () => {
    win.show()
    createDesktopShortcut()
    // El proxy NO arranca solo — el usuario hace click en Iniciar
  })
})

app.on('window-all-closed', e => e.preventDefault())
app.on('before-quit', () => { stopProxy(); if (tray) tray.destroy() })
