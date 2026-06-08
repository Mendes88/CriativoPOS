@echo off
title Criativo POS — Construir APK
color 0A
echo.
echo  =============================================
echo   CRIATIVO POS v2.3
echo  =============================================
echo.

:: Destino sem acentos, sem espaços, caminho curto
set "DESTINO=C:\CriativoPOS"

echo [1/3] A copiar projecto para %DESTINO% ...
if not exist "%DESTINO%" mkdir "%DESTINO%"
xcopy /E /I /Y "%~dp0*" "%DESTINO%\" >nul 2>&1
echo [OK] Projecto copiado

echo.
echo [2/3] A procurar Android Studio...

set "STUDIO="
for %%P in (
    "C:\Program Files\Android\Android Studio\bin\studio64.exe"
    "C:\Program Files\Android\Android Studio\bin\studio.exe"
    "%LOCALAPPDATA%\Programs\Android Studio\bin\studio64.exe"
    "%LOCALAPPDATA%\Google\AndroidStudio*\bin\studio64.exe"
) do (
    if exist %%P if "!STUDIO!"=="" set "STUDIO=%%P"
)

if defined STUDIO (
    echo [OK] Android Studio encontrado
    echo.
    echo [3/3] A abrir projecto em %DESTINO% ...
    start "" %STUDIO% "%DESTINO%"
) else (
    echo [AVISO] Android Studio nao encontrado.
    echo.
    echo Abre o Android Studio e faz:
    echo   File ^> Open ^> %DESTINO%
)

echo.
echo  =============================================
echo   No Android Studio:
echo   1. Aguarda Gradle sync
echo   2. Build ^> Generate Signed APK
echo   3. APK: app\release\app-release.apk
echo  =============================================
echo.
pause
