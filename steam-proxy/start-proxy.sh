#!/usr/bin/env bash
# ──────────────────────────────────────────────────
#  Steam Proxy v2.0 - Launcher (Linux / macOS)
# ──────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROXY_SCRIPT="$SCRIPT_DIR/steam-proxy.js"

# Verificar que Node.js esta instalado
if ! command -v node &>/dev/null; then
  echo ""
  echo "  ERROR: Node.js no esta instalado."
  echo "  Instala Node.js desde https://nodejs.org/"
  echo ""
  exit 1
fi

# Verificar que el archivo del proxy existe
if [ ! -f "$PROXY_SCRIPT" ]; then
  echo ""
  echo "  ERROR: No se encontro steam-proxy.js en $SCRIPT_DIR"
  echo ""
  exit 1
fi

echo ""
echo "  Iniciando Steam Proxy..."
echo "  Node.js: $(node --version)"
echo ""

node "$PROXY_SCRIPT"
