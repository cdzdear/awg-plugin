@echo off
:: Windows-specific build script for AWG Go library using Android NDK
:: Requires: WSL2 with Linux (for cross-compilation) OR Docker
::
:: This script sets up the cross-compilation environment and builds
:: libawg.so for Android ARM64, ARM32, and x86_64.
::
:: Alternative: Use GitHub Actions CI (workflow file included)

setlocal enabledelayedexpansion
set "SCRIPT_DIR=%~dp0"
set "GO_PATH=C:\Program Files\Go\bin"
set "PATH=%PATH%;%GO_PATH%"

echo ============================================
echo  Building AWG Library for Android (Windows)
echo ============================================

:: Check if WSL is available
where wsl >nul 2>&1
if errorlevel 1 (
    echo WSL not available. Trying Docker...
    goto :try_docker
)

echo Using WSL for cross-compilation...

:: Convert Windows path to WSL path
set "WSL_SCRIPT_DIR=/mnt/d/Загрузки/e-telegram/awg-telegram-fork/awg-go-lib"

wsl bash -c "
set -e
cd '%WSL_SCRIPT_DIR%'

# Install Go if not present in WSL
if ! command -v go &>/dev/null; then
    echo 'Installing Go in WSL...'
    wget -q https://go.dev/dl/go1.21.0.linux-amd64.tar.gz
    sudo tar -C /usr/local -xzf go1.21.0.linux-amd64.tar.gz
    export PATH=\$PATH:/usr/local/go/bin
fi

# Install Android NDK if not present
NDK_DIR=\$HOME/android-ndk-r25c
if [ ! -d \"\$NDK_DIR\" ]; then
    echo 'Downloading Android NDK r25c...'
    wget -q https://dl.google.com/android/repository/android-ndk-r25c-linux.zip
    unzip -q android-ndk-r25c-linux.zip -d \$HOME/
    rm android-ndk-r25c-linux.zip
fi
export ANDROID_NDK_ROOT=\$NDK_DIR

./build_android.sh ../android-patch/jniLibs
"

if errorlevel 1 (
    echo WSL build failed
    goto :github_actions
)
echo Build successful!
goto :end

:try_docker
echo Trying Docker...
where docker >nul 2>&1
if errorlevel 1 (
    echo Docker not available either.
    goto :github_actions
)

echo Using Docker for cross-compilation...
docker run --rm ^
    -v "%SCRIPT_DIR%:/workspace" ^
    -w "/workspace/awg-go-lib" ^
    golang:1.21 ^
    bash -c "apt-get update -q && apt-get install -qy wget unzip && ./build_android.sh ../android-patch/jniLibs"

goto :end

:github_actions
echo.
echo ============================================
echo  No cross-compilation environment found.
echo  
echo  Options to build libawg.so:
echo.
echo  1. Enable WSL2:
echo     Settings -> Windows Features -> Windows Subsystem for Linux
echo     Then: wsl --install -d Ubuntu
echo.
echo  2. Use GitHub Actions (FREE):
echo     Push code to GitHub
echo     The .github/workflows/build.yml will build automatically
echo     Download artifact from Actions tab
echo.
echo  3. Use a Linux VM or Linux machine
echo.
echo  4. Pre-built binaries:
echo     Ask maintainer for pre-built libawg.so
echo ============================================

:end
pause
