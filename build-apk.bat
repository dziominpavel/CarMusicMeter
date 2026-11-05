@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem Build the APK for the head unit. adb is NOT needed on the head unit:
rem copy dist\CarMusicMeter-*-latest.apk to a USB stick and install it there.
rem
rem Usage: build-apk.bat [debug|release] [install]
rem   (no args)  release build, signed with the keystore from local.properties.
rem              This is the mode installed on the head unit - use it by default.
rem   debug      only for readable crash stack traces. Signed with another key, so
rem              it cannot replace release without uninstalling the app, and that
rem              deletes the collected log: export logs first.
rem   install    also install via adb (phone testing only)

set "MODE=release"
set "INSTALL=0"

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="debug" set "MODE=debug"
if /i "%~1"=="release" set "MODE=release"
if /i "%~1"=="install" set "INSTALL=1"
shift
goto parse
:parsed

set "TASK=assembleRelease"
set "SRC=app\build\outputs\apk\release\app-release.apk"
if "%MODE%"=="debug" (
    set "TASK=assembleDebug"
    set "SRC=app\build\outputs\apk\debug\app-debug.apk"
)

echo === CarMusicMeter: %MODE% (%TASK%) ===
call gradlew.bat %TASK% --console=plain
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed. See the log above.
    pause
    exit /b 1
)

if not exist "%SRC%" (
    echo [ERROR] Build reported success but the file was not found: %SRC%
    pause
    exit /b 1
)

set "VER="
for /f "usebackq delims=" %%v in ("version") do set "VER=%%v"
if not defined VER set "VER=0.0.0"

if not exist "dist" mkdir "dist"
if not exist "dist\archive" mkdir "dist\archive"

set "LATEST=dist\CarMusicMeter-%MODE%-latest.apk"
set "ARCHIVED=dist\archive\CarMusicMeter-%VER%-%MODE%.apk"
copy /y "%SRC%" "%LATEST%" >nul
copy /y "%SRC%" "%ARCHIVED%" >nul

echo.
echo [OK] CarMusicMeter %VER% (%MODE%)
echo   TAKE THIS: %LATEST%
echo   history:   %ARCHIVED%
for %%F in ("%LATEST%") do echo   size:      %%~zF bytes
echo.
echo Copy the APK to the USB stick and install it on the head unit.
echo No adb required. Test scenario: docs\HEADUNIT_TEST.md

if "%INSTALL%"=="1" (
    echo.
    echo === Installing via adb ^(phone^) ===
    where adb >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] adb not found in PATH.
        pause
        exit /b 1
    )
    adb install -r "%LATEST%"
    if errorlevel 1 (
        echo [ERROR] Install failed. Check: adb devices
        pause
        exit /b 1
    )
    echo [OK] Installed.
)

echo.
pause
endlocal
