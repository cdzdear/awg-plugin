@echo off
:: AWG-Telegram Fork Setup Script for Windows
:: Clones AyuGram4A and applies the AWG tunnel integration
::
:: Prerequisites (installed automatically if missing):
::   - Git (winget install Git.Git)
::   - Go 1.21+ (winget install GoLang.Go)
::   - Android Studio / NDK (manual install required)
::
:: Usage: setup.bat [--skip-clone] [--skip-build]

setlocal enabledelayedexpansion
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%AyuGram-AWG"
set "AWG_LIB_DIR=%SCRIPT_DIR%awg-go-lib"
set "PATCH_DIR=%SCRIPT_DIR%android-patch"

echo ============================================
echo  AWG-Telegram Fork Setup
echo ============================================
echo.

:: ── Check Go installation ─────────────────────────────────────────────
echo [1/6] Checking Go...
where go >nul 2>&1
if errorlevel 1 (
    echo   Go not found in PATH. Refreshing environment...
    :: Try to find Go in standard install location
    if exist "C:\Program Files\Go\bin\go.exe" (
        set "PATH=%PATH%;C:\Program Files\Go\bin"
        echo   Found Go at C:\Program Files\Go\bin
    ) else (
        echo   ERROR: Go not found. Please restart this script after Go installation.
        echo   Install with: winget install GoLang.Go
        pause
        exit /b 1
    )
)
for /f "tokens=*" %%i in ('go version') do set GO_VERSION=%%i
echo   OK: %GO_VERSION%

:: ── Check Git installation ────────────────────────────────────────────
echo [2/6] Checking Git...
where git >nul 2>&1
if errorlevel 1 (
    if exist "C:\Program Files\Git\bin\git.exe" (
        set "PATH=%PATH%;C:\Program Files\Git\bin;C:\Program Files\Git\cmd"
    ) else (
        echo   ERROR: Git not found. Install with: winget install Git.Git
        pause
        exit /b 1
    )
)
for /f "tokens=*" %%i in ('git --version') do set GIT_VERSION=%%i
echo   OK: %GIT_VERSION%

:: ── Clone AyuGram4A ───────────────────────────────────────────────────
echo [3/6] Cloning AyuGram4A...
if "%1"=="--skip-clone" (
    echo   Skipped (--skip-clone)
    goto :build_awg_lib
)

if exist "%PROJECT_DIR%" (
    echo   Directory already exists: %PROJECT_DIR%
    echo   Use --skip-clone to skip, or delete the directory manually.
    goto :build_awg_lib
)

git clone --depth=1 https://github.com/AyuGram/AyuGram4A.git "%PROJECT_DIR%"
if errorlevel 1 (
    echo   ERROR: Failed to clone AyuGram4A
    exit /b 1
)
echo   Cloned to: %PROJECT_DIR%

:: Fetch submodules
cd "%PROJECT_DIR%"
git submodule update --init --recursive --depth=1
cd "%SCRIPT_DIR%"

:: ── Clone amneziawg-go ────────────────────────────────────────────────
echo.
echo [3b/6] Cloning amneziawg-go...
if not exist "%AWG_LIB_DIR%\vendor\amneziawg-go" (
    git clone --depth=1 https://github.com/amnezia-vpn/amneziawg-go.git ^
        "%AWG_LIB_DIR%\vendor\amneziawg-go"
)

:build_awg_lib
:: ── Build AWG Go library ───────────────────────────────────────────────
echo.
echo [4/6] Setting up AWG Go module...
cd "%AWG_LIB_DIR%"

:: Download dependencies
go mod tidy 2>&1
if errorlevel 1 (
    echo   WARNING: go mod tidy had issues (may need NDK to complete)
)

:: Install gomobile for Android cross-compilation
echo   Installing gomobile...
go install golang.org/x/mobile/cmd/gomobile@latest 2>&1

echo.
echo   NOTE: For Android .so compilation, you need:
echo     1. Android NDK r25c+
echo     2. Run: gomobile init
echo     3. Run: build_android.sh (from WSL/Linux) or build_android_windows.bat
echo.

:: ── Copy source files to AyuGram ─────────────────────────────────────
echo [5/6] Applying patches to AyuGram4A...
if not exist "%PROJECT_DIR%" (
    echo   AyuGram4A not cloned yet. Run without --skip-clone first.
    goto :show_manual_steps
)

:: Copy AWG Java source files
set "TG_SRC=%PROJECT_DIR%\TMessagesProj\src\main\java\org\telegram"
set "PATCH_SRC=%PATCH_DIR%\java\org\telegram"

:: Create target directories
if not exist "%TG_SRC%\awg" mkdir "%TG_SRC%\awg"
if not exist "%TG_SRC%\ui\awg" mkdir "%TG_SRC%\ui\awg"

:: Copy AWG module files
copy /Y "%PATCH_SRC%\awg\AWGLib.java" "%TG_SRC%\awg\AWGLib.java"
copy /Y "%PATCH_SRC%\awg\AWGManager.java" "%TG_SRC%\awg\AWGManager.java"
copy /Y "%PATCH_SRC%\ui\awg\AWGSettingsActivity.java" "%TG_SRC%\ui\awg\AWGSettingsActivity.java"

echo   Copied AWG Java files to AyuGram source tree

:: Create jniLibs directory for the .so files
set "JNI_DIR=%PROJECT_DIR%\TMessagesProj\src\main\jniLibs"
if not exist "%JNI_DIR%\arm64-v8a" mkdir "%JNI_DIR%\arm64-v8a"
if not exist "%JNI_DIR%\armeabi-v7a" mkdir "%JNI_DIR%\armeabi-v7a"
if not exist "%JNI_DIR%\x86_64" mkdir "%JNI_DIR%\x86_64"

echo   Created jniLibs directories (put libawg.so here after building)

:show_manual_steps
:: ── Show next steps ───────────────────────────────────────────────────
echo.
echo [6/6] Manual steps required:
echo ============================================
echo.
echo  A) Edit ApplicationLoader.java:
echo     File: %TG_SRC%\messenger\ApplicationLoader.java
echo     Add to onCreate():
echo       AWGLib.load(this);
echo       AWGManager.getInstance().init();
echo.
echo  B) Edit SettingsActivity.java:
echo     File: %TG_SRC%\ui\SettingsActivity.java
echo     Add "Встроенный AWG VPN" menu item
echo     See: %PATCH_DIR%\patches\02_SettingsActivity.patch
echo.
echo  C) Build libawg.so (requires Linux/WSL + NDK):
echo     cd %AWG_LIB_DIR%
echo     ./build_android.sh %JNI_DIR%
echo.
echo  D) Open %PROJECT_DIR% in Android Studio
echo     Build and run on device
echo.
echo  E) Configure your AWG server:
echo     Copy .conf file to your phone
echo     Open Telegram Settings -> AWG -> paste config
echo.
echo ============================================
echo  IMPORTANT: No VpnService is used!
echo  No VPN lock icon will appear.
echo  All encryption happens in userspace.
echo ============================================
echo.

cd "%SCRIPT_DIR%"
pause
