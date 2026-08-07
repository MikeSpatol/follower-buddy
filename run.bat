@echo off
REM Launches RuneLite with Follower Buddy loaded. See INSTALL.md.
REM Kept as a double-click for anyone who would rather not open a terminal;
REM it pauses at the end so an error is readable instead of vanishing.

cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo Java was not found on your PATH.
    echo Install a JDK 17 or newer from https://adoptium.net/ and try again.
    echo.
    pause
    exit /b 1
)

echo Starting RuneLite with Follower Buddy...
echo The first run downloads Gradle and RuneLite and takes a few minutes.
echo.

call gradlew.bat runClient

if errorlevel 1 (
    echo.
    echo That did not start cleanly. The messages above say why;
    echo the client log is also at %%USERPROFILE%%\.runelite\logs\client.log
    echo.
    pause
)
