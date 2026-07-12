@echo off
setlocal
cd /d "%~dp0"

echo [1/4] Stopping Gradle daemons...
call gradlew.bat --stop

echo [2/4] Removing stale Android and CMake outputs...
if exist .cxx rmdir /s /q .cxx
if exist build rmdir /s /q build
if exist sample\build rmdir /s /q sample\build

echo [3/4] Recreating the native build and assembling the sample app...
call gradlew.bat :sample:assembleDebug --stacktrace
if errorlevel 1 goto :failed

echo [4/4] Build completed successfully.
exit /b 0

:failed
echo Build failed. Check the error above.
exit /b 1
