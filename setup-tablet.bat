@echo off
rem One-time setup for a new tablet connected by USB (USB debugging on, all accounts removed).
rem Installs the kiosk app, makes it device owner, and starts kiosk mode.
rem Phones / Telpo terminals that should only be monitored (not locked): setup-tablet.bat monitor
cd /d "%~dp0"
set APK=final apk\mdmkiosk app.apk
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
rem Lets the App pinning override corner (6 taps top-right) sit on top of the pinned app.
adb shell appops set com.greenbotz.kiosk SYSTEM_ALERT_WINDOW allow
rem Portal screenshots (Android 11+). Note: replaces any other accessibility services on the device.
adb shell settings put secure enabled_accessibility_services com.greenbotz.kiosk/.ScreenshotService
adb shell settings put secure accessibility_enabled 1
rem Portal screenshots on Android 9/10: allow screen capture without the "Start now" prompt.
adb shell appops set com.greenbotz.kiosk PROJECT_MEDIA allow
rem Bug Finder: lets the app read every app's crash / freeze reports.
adb shell pm grant com.greenbotz.kiosk android.permission.READ_LOGS
adb shell pm grant com.greenbotz.kiosk android.permission.READ_DROPBOX_DATA
adb shell appops set com.greenbotz.kiosk GET_USAGE_STATS allow
adb shell am start -n com.greenbotz.kiosk/.KioskActivity --es mode %MODE%
echo.
echo Done. On the tablet, finish setup: Production or Debug, Device ID, allowed apps.
pause
exit /b 0

:fail
echo.
echo Install failed. Check the cable, USB debugging, and that only one tablet is connected.
pause
exit /b 1
