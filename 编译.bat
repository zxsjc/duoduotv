@echo off
title APK 编译工具

:: 1. 配置你的核心环境路径
set "JAVA_HOME=C:\Java\jdk-17.0.2"
set "ANDROID_HOME=C:\AndroidSDK"
set "GRADLE_HOME=C:\Gradle\gradle-8.11.1"
set "PATH=%JAVA_HOME%\bin;%GRADLE_HOME%\bin;%PATH%"

:: 2. 强制指定绝对路径测试 Java
echo [1/4] 正在测试 Java...
call "%JAVA_HOME%\bin\java.exe" -version
if %errorlevel% neq 0 goto error

:: 3. 强制指定绝对路径测试 Gradle
echo.
echo [2/4] 正在测试 Gradle...
call "%GRADLE_HOME%\bin\gradle.bat" -v
if %errorlevel% neq 0 goto error

:: 4. 生成 SDK 配置文件
echo.
echo [3/4] 正在配置 SDK...
if not exist local.properties (
    echo sdk.dir=C\:\\AndroidSDK> local.properties
)

:: 5. 开始编译
echo.
echo [4/4] 开始编译 APK，请耐心等待...
echo ----------------------------------------
call "%GRADLE_HOME%\bin\gradle.bat" assembleDebug

if %errorlevel% equ 0 goto success
goto fail

:success
echo.
echo ========================================
echo   编译成功！
echo   APK 位于: app\build\outputs\apk\debug\
echo ========================================
:: 自动打开存放 APK 的文件夹
explorer "app\build\outputs\apk\debug"
pause
exit /b

:fail
echo.
echo *** 编译失败！请查看上方日志 ***
pause
exit /b

:error
echo.
echo [错误] 环境不全，请检查 C 盘文件夹名称是否正确。
pause
exit /b