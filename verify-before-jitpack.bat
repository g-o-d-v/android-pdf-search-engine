@echo off
setlocal
cd /d "%~dp0"

echo [1/2] Optional repository checks...
where python >nul 2>&1
if errorlevel 1 (
  echo Python is not installed. Skipping optional helper checks.
) else (
  python tools\check_jitpack_ready.py
  if errorlevel 1 exit /b 1
  python tools\verify_native_libs.py --require-abis arm64-v8a,armeabi-v7a src\main\jniLibs
  if errorlevel 1 exit /b 1
)

echo [2/2] Running the same Maven publication task used by JitPack...
call gradlew.bat clean verifyReleaseInputs publishReleasePublicationToMavenLocal --stacktrace
if errorlevel 1 exit /b 1

echo.
echo JitPack preflight passed.
echo Published locally under %%USERPROFILE%%\.m2\repository\com\github\g-o-d-v\android-pdf-search-engine\
endlocal
