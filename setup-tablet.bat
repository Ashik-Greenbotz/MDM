@echo off
rem One-time setup for a new tablet connected by USB (USB debugging on, all accounts removed).
rem Installs the kiosk app, makes it device owner, and starts kiosk mode.
rem Phones / Telpo terminals that should only be monitored (not locked): setup-tablet.bat monitor
cd /d "%~dp0"
set APK=Kiosk app phase2.apk
if not exist "%APK%" set APK=app\build\outputs\apk\debug\app-debug.apk

adb devices
echo.
echo Installing %APK% ...
adb install -r "%APK%" || goto :fail
echo Making the kiosk app device owner ...
adb shell dpm set-device-owner com.greenbotz.kiosk/.AdminReceiver
rem Fails harmlessly if it is already device owner; fails for real if an account is still on the tablet.
set MODE=kiosk
if /i "%~1"=="monitor" set MODE=monitor
echo Mode: %MODE%
rem Lets the App pinning override corner (6 taps bottom-left) sit on top of the pinned app.
adb shell appops set com.greenbotz.kiosk SYSTEM_ALERT_WINDOW allow
adb shell am start -n com.greenbotz.kiosk/.KioskActivity --es mode %MODE%
echo.
echo Done. The tablet should now show the kiosk screen and appear on the dashboard within a minute.
pause
exit /b 0

:fail
echo.
echo Install failed. Check the cable, USB debugging, and that only one tablet is connected.
pause
exit /b 1
