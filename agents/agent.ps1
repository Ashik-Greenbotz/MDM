# Windows device agent: checks in with the kiosk server so this PC shows on the dashboard.
# Run:  powershell -ExecutionPolicy Bypass -File agent.ps1 -Server http://192.168.68.50:8080 -Key change-me-device-key
# Run at startup: Task Scheduler -> "At log on" -> the command above (tick "Run with highest privileges" for reboot).
param(
  [string]$Server = "http://192.168.68.50:8080",
  [string]$Key = "change-me-device-key",
  [int]$EverySeconds = 30
)
$ErrorActionPreference = "Stop"
# Stable id per machine (Windows install GUID).
$id = "win-" + (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Cryptography").MachineGuid
$os = Get-CimInstance Win32_OperatingSystem
$cs = Get-CimInstance Win32_ComputerSystem
$serial = (Get-CimInstance Win32_BIOS).SerialNumber

while ($true) {
  try {
    $os = Get-CimInstance Win32_OperatingSystem
    $battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1
    $disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    $ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.PrefixOrigin -ne "WellKnown" } | Select-Object -First 1).IPAddress
    $body = @{
      id = $id; type = "windows"; deviceName = $env:COMPUTERNAME; model = "$($cs.Manufacturer) $($cs.Model)"
      serial = $serial; os = $os.Caption; localIp = $ip
      uptime = [int]((Get-Date) - $os.LastBootUpTime).TotalSeconds
      info = @{
        "Free disk C:" = "{0:N1} GB" -f ($disk.FreeSpace / 1GB)
        "Free memory" = "{0:N1} GB" -f ($os.FreePhysicalMemory / 1MB)
        "User" = $cs.UserName
      }
    }
    if ($battery) { $body.battery = [int]$battery.EstimatedChargeRemaining; $body.charging = $battery.BatteryStatus -eq 2 }
    $reply = Invoke-RestMethod -Method Post -Uri "$Server/api/report" -Headers @{ "X-Device-Key" = $Key } `
      -ContentType "application/json" -Body ($body | ConvertTo-Json -Depth 3)
    if ($reply.command.type -eq "reboot") { Restart-Computer -Force }
  } catch {
    Write-Warning "check-in failed: $_"
  }
  Start-Sleep -Seconds $EverySeconds
}
