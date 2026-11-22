@echo off
chcp 65001 >nul
setlocal

rem ============================================================
rem  Build the three flavors, rename outputs to the names the
rem  in-app updater expects, then generate the update manifests.
rem  Just double-click, or run:  build_release.bat
rem ============================================================

rem ---- toolchain --------------------------------------------------
set "JAVA_HOME=C:\Java\jdk-17.0.2"
set "ANDROID_HOME=C:\AndroidSDK"
set "GRADLE_BIN=C:\Gradle\gradle-8.11.1\bin"

rem ---- release version (edit these two lines per release) --------
set "VERSION_CODE=12"
set "VERSION_NAME=2.1.0"

rem ---- where the ready-to-upload files go ------------------------
set "OUTDIR=%~dp0release_out"

cd /d "%~dp0"
set "PATH=%GRADLE_BIN%;%PATH%"

echo.
echo [1/3] Build arm32 + arm64 + x86  (versionCode=%VERSION_CODE% versionName=%VERSION_NAME%)
echo -----------------------------------------------------------
call gradle.bat assembleArm32Debug assembleArm64Debug assembleX86Debug -PversionCodeOverride=%VERSION_CODE% -PversionNameOverride=%VERSION_NAME% --console=plain --no-daemon
if errorlevel 1 goto :failed

echo.
echo [2/3] Collect APKs with updater-compatible file names
echo -----------------------------------------------------------
if not exist "%OUTDIR%" mkdir "%OUTDIR%"
copy /y "app\build\outputs\apk\arm32\debug\app-arm32-debug.apk" "%OUTDIR%\nTv.apk"     >nul || goto :failed
copy /y "app\build\outputs\apk\arm64\debug\app-arm64-debug.apk" "%OUTDIR%\nTv64.apk"   >nul || goto :failed
copy /y "app\build\outputs\apk\x86\debug\app-x86-debug.apk"     "%OUTDIR%\nTvX86.apk" >nul || goto :failed

echo.
echo [3/3] Generate version.json / version-lite.json
echo -----------------------------------------------------------
call gradle.bat :app:generateVersionFile -PupdateApk32="%OUTDIR%\nTv.apk" -PupdateApk64="%OUTDIR%\nTv64.apk" -PupdateApkX86="%OUTDIR%\nTvX86.apk" -PversionCodeOverride=%VERSION_CODE% -PversionNameOverride=%VERSION_NAME% -PimportantUpdate=true --console=plain --no-daemon
if errorlevel 1 goto :failed

echo.
echo ===========================================================
echo  Ready to upload  -  folder: %OUTDIR%
echo ===========================================================
dir /b "%OUTDIR%"
echo.
echo  SHA256 (compare with the values inside version.json)
echo -----------------------------------------------------------
for %%F in ("%OUTDIR%\nTv.apk" "%OUTDIR%\nTv64.apk" "%OUTDIR%\nTvX86.apk") do (
    echo %%~nxF
    certutil -hashfile "%%~fF" SHA256 | findstr /r /x "[0-9a-fA-F][0-9a-fA-F]*"
)
echo.
echo  Manifests written to:
echo    %~dp0version.json
echo    %~dp0version-lite.json
echo.
echo Done.
exit /b 0

:failed
echo.
echo *** BUILD FAILED - see the messages above ***
exit /b 1
