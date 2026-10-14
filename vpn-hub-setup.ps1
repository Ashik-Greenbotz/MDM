# VPN hub on the office PC (Windows): run ONCE in PowerShell as administrator, with the portal server started at least once
# with WG_ENDPOINT set (it writes server\wireguard\mdm-hub.conf).
#   powershell -ExecutionPolicy Bypass -File vpn-hub-setup.ps1
# Installs WireGuard for Windows, runs the hub as the tunnel service "mdm-hub", lets VPN peers reach each other through it,
# opens UDP 51820, and adds a SYSTEM task that applies peer changes from the portal within a few seconds
# (the portal server runs without admin rights and can't change the tunnel itself).
$ErrorActionPreference = "Stop"
$conf = Join-Path $PSScriptRoot "server\wireguard\mdm-hub.conf"
$sync = Join-Path $PSScriptRoot "server\wireguard\mdm-hub-sync.conf"
if (-not (Test-Path $conf)) { throw "No $conf yet: start the portal with WG_ENDPOINT set first." }
$wg = Join-Path $env:ProgramFiles "WireGuard"
if (-not (Test-Path "$wg\wireguard.exe")) {
  "Installing WireGuard..."
  $msi = (Invoke-WebRequest -UseBasicParsing https://download.wireguard.com/windows-client/).Content |
    Select-String -Pattern 'wireguard-amd64-[\d.]+\.msi' -AllMatches | ForEach-Object { $_.Matches.Value } | Sort-Object { [version]($_ -replace '^wireguard-amd64-|\.msi$') } | Select-Object -Last 1
  Invoke-WebRequest -UseBasicParsing "https://download.wireguard.com/windows-client/$msi" -OutFile "$env:TEMP\$msi"
  Start-Process msiexec.exe -ArgumentList "/i `"$env:TEMP\$msi`" /qn DO_NOT_LAUNCH=1" -Wait
}
"Starting the hub tunnel mdm-hub..."
& "$wg\wireguard.exe" /uninstalltunnelservice mdm-hub 2>$null; Start-Sleep 2
& "$wg\wireguard.exe" /installtunnelservice $conf
for ($i = 0; $i -lt 20 -and -not (Get-NetAdapter -Name mdm-hub -ErrorAction SilentlyContinue); $i++) { Start-Sleep 1 }
# peers (devices, admin laptops) reach each other through this PC
Set-NetIPInterface -InterfaceAlias mdm-hub -Forwarding Enabled
if (-not (Get-NetFirewallRule -Name MDM-VPN-In -ErrorAction SilentlyContinue)) {
  New-NetFirewallRule -Name MDM-VPN-In -DisplayName "Device portal VPN (WireGuard)" -Direction Inbound -Protocol UDP -LocalPort 51820 -Action Allow -Profile Any | Out-Null }
if (-not (Get-NetFirewallRule -Name MDM-VPN-Peers -ErrorAction SilentlyContinue)) {
  New-NetFirewallRule -Name MDM-VPN-Peers -DisplayName "Device portal VPN peers" -Direction Inbound -RemoteAddress 10.66.0.0/16 -Action Allow -Profile Any | Out-Null }
# watcher: apply the portal's peer list whenever it changes (also re-enables forwarding after a restart)
$watch = Join-Path $PSScriptRoot "server\wireguard\mdm-hub-watch.ps1"
Set-Content -Path $watch -Encoding ASCII -Value @"
`$last = ''
while (`$true) {
  try {
    `$h = (Get-FileHash '$sync').Hash
    if (`$h -ne `$last) { & '$wg\wg.exe' syncconf mdm-hub '$sync'; if (`$LASTEXITCODE -eq 0) { `$last = `$h } }
    Set-NetIPInterface -InterfaceAlias mdm-hub -Forwarding Enabled -ErrorAction SilentlyContinue
  } catch { }
  Start-Sleep 3
}
"@
$a = New-ScheduledTaskAction -Execute powershell.exe -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$watch`""
$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit 0 -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "MDM VPN hub sync" -Action $a -Trigger (New-ScheduledTaskTrigger -AtStartup) -Settings $s -User SYSTEM -RunLevel Highest -Force | Out-Null
Start-ScheduledTask "MDM VPN hub sync"
Start-Sleep 4
& "$wg\wg.exe" show mdm-hub
"VPN hub ready."
