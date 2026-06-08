@echo off
:: ============================================
:: FIX CRITICO: Desactiva LANCache falso
:: El router devuelve 10.0.0.2 para lancache.steamcontent.com
:: haciendo que Steam descargue de tu propia maquina (falla)
:: Este fix apunta a los servidores reales de Valve en Lima
:: Ejecutar como ADMINISTRADOR
:: ============================================

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Ejecuta este script como ADMINISTRADOR
    echo Click derecho - Ejecutar como administrador
    pause
    exit /b 1
)

set HOSTS=%SystemRoot%\System32\drivers\etc\hosts

findstr /C:"STEAM-PROXY-START" "%HOSTS%" >nul 2>&1
if %errorlevel% equ 0 (
    echo Ya existen entradas Steam. Limpiando primero...
    powershell -Command "$c = Get-Content '%HOSTS%' -Raw; $c = $c -replace '(?ms)\r?\n?# STEAM-PROXY-START.*?# STEAM-PROXY-END\r?\n?',''; Set-Content '%HOSTS%' $c.TrimEnd() -NoNewline"
)

echo.
echo Agregando IPs reales de Steam al archivo hosts...
echo.

>> "%HOSTS%" echo.
>> "%HOSTS%" echo # STEAM-PROXY-START
>> "%HOSTS%" echo # Fix: Desactiva LANCache falso (router devuelve 10.0.0.2)
>> "%HOSTS%" echo # y apunta a servidores reales de Valve en Lima
>> "%HOSTS%" echo 155.133.244.4       lancache.steamcontent.com
>> "%HOSTS%" echo # Cache servers Lima
>> "%HOSTS%" echo 155.133.244.4       cache1-lim1.steamcontent.com
>> "%HOSTS%" echo 155.133.244.20      cache2-lim1.steamcontent.com
>> "%HOSTS%" echo 155.133.244.3       cache3-lim1.steamcontent.com
>> "%HOSTS%" echo 155.133.244.19      cache4-lim1.steamcontent.com
>> "%HOSTS%" echo 155.133.244.4       cache5-lim1.steamcontent.com
>> "%HOSTS%" echo 155.133.244.20      cache6-lim1.steamcontent.com
>> "%HOSTS%" echo 155.133.244.3       cache7-lim1.steamcontent.com
>> "%HOSTS%" echo # STEAM-PROXY-END

ipconfig /flushdns >nul 2>&1

echo.
echo LISTO! Entradas agregadas:
echo   lancache.steamcontent.com    -^> 155.133.244.4  (Valve Lima real)
echo   cache1-7-lim1.steamcontent   -^> IPs reales Lima
echo.
echo IMPORTANTE: Reinicia Steam completamente
echo   (Click derecho en icono Steam -^> Salir, luego abrir de nuevo)
echo.
pause
