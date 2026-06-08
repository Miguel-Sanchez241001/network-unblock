@echo off
:: ============================================
:: Agrega IPs de Steam al archivo hosts
:: Ejecutar como ADMINISTRADOR
:: ============================================

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Ejecuta este script como ADMINISTRADOR
    echo Click derecho ^> Ejecutar como administrador
    pause
    exit /b 1
)

set HOSTS=%SystemRoot%\System32\drivers\etc\hosts

echo.
echo ============================================
echo  Configurando hosts para Steam
echo ============================================
echo.

:: Verificar si ya estan agregadas
findstr /C:"# STEAM-PROXY-START" "%HOSTS%" >nul 2>&1
if %errorlevel% equ 0 (
    echo Ya existen entradas de Steam en el archivo hosts.
    echo Usa remove-hosts.bat para quitarlas primero.
    pause
    exit /b 0
)

echo Agregando entradas al archivo hosts...

>> "%HOSTS%" echo.
>> "%HOSTS%" echo # STEAM-PROXY-START - No editar manualmente
>> "%HOSTS%" echo # Servicios principales
>> "%HOSTS%" echo 23.57.121.205       store.steampowered.com
>> "%HOSTS%" echo 96.6.206.56         api.steampowered.com
>> "%HOSTS%" echo 96.6.206.56         steamcommunity.com
>> "%HOSTS%" echo 96.6.206.56         www.steamcommunity.com
>> "%HOSTS%" echo 96.6.206.56         login.steampowered.com
>> "%HOSTS%" echo 96.6.206.56         help.steampowered.com
>> "%HOSTS%" echo 96.6.206.56         checkout.steampowered.com
>> "%HOSTS%" echo 23.196.72.39        steam.tv
>> "%HOSTS%" echo # Client Manager
>> "%HOSTS%" echo 162.254.195.44      cm0.steampowered.com
>> "%HOSTS%" echo # CDN / Estatico
>> "%HOSTS%" echo 200.60.190.10       cdn.cloudflare.steamstatic.com
>> "%HOSTS%" echo 200.60.190.10       cdn.akamai.steamstatic.com
>> "%HOSTS%" echo 200.60.190.10       community.cloudflare.steamstatic.com
>> "%HOSTS%" echo 200.60.190.24       store.cloudflare.steamstatic.com
>> "%HOSTS%" echo 200.60.190.26       shared.cloudflare.steamstatic.com
>> "%HOSTS%" echo 200.60.190.224      client-update.akamai.steamstatic.com
>> "%HOSTS%" echo 199.232.211.52      avatars.steamstatic.com
>> "%HOSTS%" echo 200.60.190.19       steambroadcast.akamaized.net
>> "%HOSTS%" echo 200.60.190.10       steamcdn-a.akamaihd.net
>> "%HOSTS%" echo # Descargas
>> "%HOSTS%" echo 155.133.255.9       lancache.steamcontent.com
>> "%HOSTS%" echo # STEAM-PROXY-END

echo.
echo Limpiando cache DNS...
ipconfig /flushdns

echo.
echo LISTO! Entradas de Steam agregadas.
echo Reinicia Steam para que tome efecto.
echo.
pause
