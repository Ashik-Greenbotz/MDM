@echo off
rem Starts the kiosk server and dashboard. Keep this window open while tablets are in use.
rem Change the password and key before real use. DEVICE_KEY must match server_key in
rem app\src\main\res\values\server.xml (rebuild the app after changing it).
set PORT=8080
set ADMIN_USER=admin
set ADMIN_PASSWORD=admin1234
set DEVICE_KEY=change-me-device-key

cd /d "%~dp0server"
start "" http://localhost:%PORT%
node server.js
pause
