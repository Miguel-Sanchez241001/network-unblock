# ──────────────────────────────────────────────────
#  Steam Proxy v2.0 - Launcher (Windows PowerShell)
# ──────────────────────────────────────────────────

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProxyScript = Join-Path $ScriptDir "steam-proxy.js"

# Verificar que Node.js esta instalado
$nodePath = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodePath) {
    Write-Host ""
    Write-Host "  ERROR: Node.js no esta instalado." -ForegroundColor Red
    Write-Host "  Instala Node.js desde https://nodejs.org/"
    Write-Host ""
    exit 1
}

# Verificar que el archivo del proxy existe
if (-not (Test-Path $ProxyScript)) {
    Write-Host ""
    Write-Host "  ERROR: No se encontro steam-proxy.js en $ScriptDir" -ForegroundColor Red
    Write-Host ""
    exit 1
}

$nodeVersion = & node --version
Write-Host ""
Write-Host "  Iniciando Steam Proxy..."
Write-Host "  Node.js: $nodeVersion"
Write-Host ""

& node $ProxyScript
