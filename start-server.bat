@echo off
rem Starts the kiosk server and dashboard. Keep this window open while tablets are in use.
rem Password and device key come from functions\.env (not in git; copy functions\.env.example to start).
set PORT=8080
if not exist "%~dp0functions\.env" (
  echo Missing functions\.env - copy functions\.env.example to functions\.env and set a password.
  pause
  exit /b 1
)
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0functions\.env") do set "%%a=%%b"

rem Free Cloudflare tunnel: opens a second window showing the https://....trycloudflare.com address
rem to use from outside the office. The address changes every time this starts.
start "Cloudflare tunnel" cloudflared tunnel --url http://localhost:%PORT%

cd /d "%~dp0functions"
start "" http://localhost:%PORT%
node server.js
pause
