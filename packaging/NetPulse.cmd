@echo off
setlocal
rem Use the bundled, vendor-signed runtime. No installed Java or Maven is required.
start "" "%~dp0runtime\bin\javaw.exe" --enable-native-access=ALL-UNNAMED -cp "%~dp0app\@APP_JAR@" com.netpulse.DesktopLauncher
endlocal
