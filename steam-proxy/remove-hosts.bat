@echo off
:: Remueve las entradas de Steam del archivo hosts
:: Ejecutar como ADMINISTRADOR

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Ejecuta este script como ADMINISTRADOR
    pause
    exit /b 1
)

set HOSTS=%SystemRoot%\System32\drivers\etc\hosts
set TEMP_HOSTS=%TEMP%\hosts_clean.tmp

echo Removiendo entradas de Steam...

powershell -Command "$content = Get-Content '%HOSTS%' -Raw; $pattern = '(?ms)\r?\n?# STEAM-PROXY-START.*?# STEAM-PROXY-END\r?\n?'; $content = $content -replace $pattern, ''; Set-Content -Path '%HOSTS%' -Value $content.TrimEnd() -NoNewline"

ipconfig /flushdns

echo.
echo LISTO! Entradas de Steam removidas del archivo hosts.
echo.
pause
