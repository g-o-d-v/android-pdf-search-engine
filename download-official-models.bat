@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat prepareOfficialOcrModels --stacktrace
if errorlevel 1 (
  echo.
  echo Official OCR model download failed. See the error above.
  exit /b 1
)
echo.
echo Official OCR models are ready.
endlocal
