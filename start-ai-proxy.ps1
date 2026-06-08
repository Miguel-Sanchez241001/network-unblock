# ──────────────────────────────────────────────────
#  AI Proxy v1.0 - Launcher (Windows PowerShell)
#  ChatGPT (OpenAI) + Claude (Anthropic)
#  Puerto: 127.0.0.1:8889
#
#  Uso normal (con fragmentacion TLS):
#    .\start-ai-proxy.ps1
#
#  Modo diagnostico (sin fragmentacion, para comparar):
#    .\start-ai-proxy.ps1 -NoSplit
# ──────────────────────────────────────────────────

param(
    [switch]$NoSplit
)

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProxyScript = Join-Path $ScriptDir "ai-proxy.js"

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
    Write-Host "  ERROR: No se encontro ai-proxy.js en $ScriptDir" -ForegroundColor Red
    Write-Host ""
    exit 1
}

$nodeVersion = & node --version
Write-Host ""
Write-Host "  Iniciando AI Proxy (ChatGPT + Claude)..." -ForegroundColor Cyan
Write-Host "  Node.js: $nodeVersion"
Write-Host "  Puerto:  127.0.0.1:8889"

if ($NoSplit) {
    Write-Host "  Modo:    SIN fragmentacion TLS (diagnostico)" -ForegroundColor Yellow
} else {
    Write-Host "  Modo:    Con fragmentacion TLS (normal)" -ForegroundColor Green
}

Write-Host ""
Write-Host "  Configura tu navegador o sistema con este proxy:" -ForegroundColor Yellow
Write-Host "    Host:   127.0.0.1"
Write-Host "    Puerto: 8889"
Write-Host ""

if ($NoSplit) {
    $env:NO_SPLIT = "1"
} else {
    $env:NO_SPLIT = "0"
}

& node $ProxyScript
