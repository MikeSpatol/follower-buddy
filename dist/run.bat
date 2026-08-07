@echo off
REM Follower Buddy - runnable bundle. See INSTALL-BUNDLE.md.
REM
REM Everything needed is in lib\. No Gradle, no build, no download.
REM -ea is required: RuneLite's loadBuiltin hard-fails without assertions.

cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo.
    echo Java was not found.
    echo.
    echo Install Java 17 or newer from https://adoptium.net/ then run this again.
    echo.
    pause
    exit /b 1
)

echo Starting RuneLite with Follower Buddy...
echo.

java -ea -cp "lib\*" com.follower.FollowerPluginTest --developer-mode

if errorlevel 1 (
    echo.
    echo That did not start cleanly - the messages above say why.
    echo The client log is at %%USERPROFILE%%\.runelite\logs\client.log
    echo.
    pause
)
