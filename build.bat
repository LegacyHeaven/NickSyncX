@echo off
setlocal EnableExtensions
title NickSyncX Build
cd /d "%~dp0"

echo.
echo ============================================================
echo  NickSyncX - clean build
echo ============================================================
echo Project: %CD%
echo.

if not defined JAVA_HOME if exist "C:\Program Files\Zulu\zulu-25\bin\java.exe" set "JAVA_HOME=C:\Program Files\Zulu\zulu-25"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found in PATH. Install JDK 25 and set JAVA_HOME.
    pause
    exit /b 1
)

echo [INFO] Java version:
java -version
if errorlevel 1 (
    echo [ERROR] Java could not start. Check JAVA_HOME.
    pause
    exit /b 2
)

if exist "gradlew.bat" (
    set "GRADLE_CMD=gradlew.bat"
) else (
    where gradle >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Gradle was not found in PATH and no gradlew.bat exists.
        echo Install the Gradle version required by the project or add Gradle to PATH.
        pause
        exit /b 3
    )
    set "GRADLE_CMD=gradle"
)

echo.
echo [INFO] Running clean build. The first run may download dependencies.
call %GRADLE_CMD% --no-daemon clean build --stacktrace > "build-log.txt" 2>&1
if errorlevel 1 (
    echo.
    echo [BUILD FAILED] Gradle log follows:
    echo ------------------------------------------------------------
    type "build-log.txt"
    echo ------------------------------------------------------------
    echo No output JARs were copied to dist.
    pause
    exit /b 4
)

if not exist "paper\build\libs\paper-1.4.0.jar" (
    echo [ERROR] Paper/Purpur JAR missing: paper\build\libs\paper-1.4.0.jar
    echo See build-log.txt.
    pause
    exit /b 5
)
if not exist "velocity\build\libs\velocity-1.4.0.jar" (
    echo [ERROR] Shaded Velocity JAR missing: velocity\build\libs\velocity-1.4.0.jar
    echo Do not use the -plain.jar.
    echo See build-log.txt.
    pause
    exit /b 6
)

if not exist "dist" mkdir "dist"
copy /Y "paper\build\libs\paper-1.4.0.jar" "dist\NickSyncX-Paper-1.4.0.jar" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy Paper JAR.
    pause
    exit /b 7
)
copy /Y "velocity\build\libs\velocity-1.4.0.jar" "dist\NickSyncX-Velocity-1.4.0.jar" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy Velocity JAR.
    pause
    exit /b 8
)

echo.
echo ============================================================
echo  BUILD SUCCESSFUL
echo ============================================================
echo Paper/Purpur: dist\NickSyncX-Paper-1.4.0.jar
echo Velocity:     dist\NickSyncX-Velocity-1.4.0.jar
echo Full build log: build-log.txt
echo.
echo Install the Paper JAR on each backend and the shaded Velocity JAR on the proxy.
echo Back up plugins\LegacyNickSync and plugins\NickSyncX before upgrading.
echo.
pause
exit /b 0
