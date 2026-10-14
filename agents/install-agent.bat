@echo off
rem Installs the device portal agent on this Windows PC: copies agent.ps1 to C:\mdm and runs it at every start-up as
rem SYSTEM (so Restart, Shut down and Run command work), also on battery, restarted if it ever stops.
rem Keep agent.ps1 in the same folder as this file. Right-click -> Run as administrator.
net session >nul 2>&1
if errorlevel 1 (
  echo Right-click install-agent.bat and choose "Run as administrator".
  pause
  exit /b 1
)
if not exist "%~dp0agent.ps1" (
  echo agent.ps1 must be in the same folder as this file.
  pause
  exit /b 1
)
set "SERVER=%~1"
if "%SERVER%"=="" set /p "SERVER=Portal address, e.g. http://192.168.68.57:8080 : "
set /p "KEY=PC agent key (AGENT_KEY in functions\.env on the server PC) : "
mkdir C:\mdm 2>nul
copy /y "%~dp0agent.ps1" C:\mdm\agent.ps1 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$a = New-ScheduledTaskAction -Execute powershell.exe -Argument '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\mdm\agent.ps1 -Server %SERVER% -Key %KEY%'; $s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew; Stop-ScheduledTask 'MDM agent' -ErrorAction SilentlyContinue; Register-ScheduledTask -TaskName 'MDM agent' -Action $a -Trigger (New-ScheduledTaskTrigger -AtStartup) -Settings $s -User SYSTEM -RunLevel Highest -Force | Out-Null; Start-ScheduledTask 'MDM agent'; Start-Sleep 3; 'Agent: ' + (Get-ScheduledTask 'MDM agent').State"
echo.
echo Done. This PC shows in the portal's Devices list within a minute.
echo To remove it: schtasks /delete /f /tn "MDM agent"
pause
