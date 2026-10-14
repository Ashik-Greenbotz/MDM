# Windows device agent: checks in with the device portal so this PC shows on the dashboard, and runs the portal's
# commands: restart, shut down, lock screen, log off, message, screenshot, run a PowerShell command, send / install a file, logs.
# Run:  powershell -ExecutionPolicy Bypass -File agent.ps1 -Server http://192.168.68.57:8080 -Key <AGENT_KEY>
# Install for good: agents\install-agent.bat (as administrator). It runs as SYSTEM at start-up, so Restart and Run work.
# Updates itself: when the server has a newer $Version it downloads this file again and restarts.
param(
  [string]$Server = "http://192.168.68.57:8080",
  [string]$Key = "change-me-device-key",
  [int]$EverySeconds = 10
)
$Version = 16  # 16: WireGuard VPN (tunnel service mdm-vpn), temperature from ACPI thermal zones
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
# Stable id per machine (Windows install GUID).
$id = "win-" + (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Cryptography").MachineGuid
$cs = Get-CimInstance Win32_ComputerSystem
$serial = (Get-CimInstance Win32_BIOS).SerialNumber
# Fixed device details for the device page (read once; failures just leave a detail out).
$details = [ordered]@{}
try {
  $cpuInfo = Get-CimInstance Win32_Processor | Select-Object -First 1
  $nt = Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion"
  $bios = Get-CimInstance Win32_BIOS
  $details["Manufacturer"] = "$($cs.Manufacturer)"
  $details["CPU"] = ("$($cpuInfo.Name)" -replace '\s+', ' ').Trim()
  $details["Cores"] = "$($cpuInfo.NumberOfCores) cores / $($cpuInfo.NumberOfLogicalProcessors) threads"
  $details["Total memory"] = "{0:N1} GB" -f ($cs.TotalPhysicalMemory / 1GB)
  $details["Windows"] = "$($nt.DisplayVersion) (build $($nt.CurrentBuild).$($nt.UBR))"
  $details["BIOS"] = "$($bios.Manufacturer) $($bios.SMBIOSBIOSVersion)"
  $details["GPU"] = (Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name }) -join ", "
  $details["Domain / workgroup"] = $(if ($cs.PartOfDomain) { "Domain $($cs.Domain)" } else { "Workgroup $($cs.Workgroup)" })
} catch { }
$headers = @{ "X-Device-Key" = $Key; "X-Device-Id" = $id }
$dir = Split-Path -Parent $PSCommandPath
$pub = $env:PUBLIC   # C:\Users\Public: the signed-in user's helper task can read and write here

function Send-Result($cmd, $ok, $output) {
  $body = @{ type = $cmd.type; name = "$($cmd.name)"; ok = $ok; output = "$output" } | ConvertTo-Json
  Invoke-RestMethod -DisableKeepAlive -Method Post -Uri "$Server/api/command-result" -Headers $headers -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) | Out-Null
}

# Lock, log off, message and screenshot need the signed-in user's desktop, which SYSTEM can't reach: run the script
# as a one-off task in the user's session (any member of Users) and wait up to $wait seconds for it.
# Returns "" when it worked, else what went wrong, for the portal. The helper writes "ok" (or its error) to mdm-user.log
# when it has really finished; the agent waits for that file (the task state isn't reliable right after starting).
# $name: the message pop-up has its own task, so an open message (until the user clicks OK) never blocks lock / screenshot.
function Invoke-AsUser($script, [int]$wait = 20, $name = "MDM user action") {
  $base = Join-Path $pub ($name -replace " ", "-").ToLower()
  $f = "$base.ps1"; $userLog = "$base.log"
  Stop-ScheduledTask $name -ErrorAction SilentlyContinue # a helper left hanging would make Windows refuse the new one
  Remove-Item $userLog -ErrorAction SilentlyContinue
  Set-Content -Path $f -Encoding UTF8 -Value "try {`n$script`n'ok' | Set-Content '$userLog'`n} catch { `$_ | Out-String | Set-Content '$userLog' }"
  $act = New-ScheduledTaskAction -Execute powershell.exe -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$f`""
  # as the signed-in user, in their desktop session (Interactive: no password needed)
  $who = New-ScheduledTaskPrincipal -UserId (Get-SignedInUser) -LogonType Interactive -RunLevel Limited
  # allowed on battery: Windows otherwise leaves the task Queued on a laptop that is unplugged
  $set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 5)
  Register-ScheduledTask -TaskName $name -Action $act -Principal $who -Settings $set -Force | Out-Null
  Start-ScheduledTask $name
  if ($wait -le 0) { return "" }
  for ($i = 0; $i -lt $wait -and -not (Test-Path $userLog); $i++) { Start-Sleep 1 }
  Start-Sleep -Milliseconds 300 # let it finish writing
  $r = "$(Get-Content $userLog -Raw -ErrorAction SilentlyContinue)".Trim()
  if ($r -eq "ok") { return "" }
  if ($r) { return $r }
  $info = Get-ScheduledTaskInfo $name
  return "The helper didn't finish within $wait s (task $((Get-ScheduledTask $name).State), result $($info.LastTaskResult))"
}
function Get-SignedInUser { (Get-CimInstance Win32_ComputerSystem).UserName }

# ---- remote access ----
$rustdesk = Join-Path $env:ProgramFiles "RustDesk\rustdesk.exe"
$remoteInfo = $null; $remoteAt = [datetime]::MinValue; $tunnel = $null
# What the portal shows for remote access (checked once a minute; reading the RustDesk ID is slow).
function Get-RemoteInfo {
  if ($script:remoteInfo -and ((Get-Date) - $script:remoteAt).TotalSeconds -lt 60) { return $script:remoteInfo }
  $svc = Get-Service sshd -ErrorAction SilentlyContinue
  $id = $null
  if (Test-Path $rustdesk) { try { $id = "$(& $rustdesk --get-id | Out-String)".Trim() } catch { } }
  $user = "$(Get-SignedInUser)"
  if (-not $user) { $user = "$((Get-LocalGroupMember -SID S-1-5-32-544 -ErrorAction SilentlyContinue | Where-Object PrincipalSource -eq Local | Select-Object -First 1).Name)" }
  $script:remoteInfo = @{
    ssh = [bool]($svc -and $svc.Status -eq "Running"); sshInstalled = [bool]$svc; sshUser = ($user -split '\\')[-1]
    rustdesk = [bool](Test-Path $rustdesk); rustdeskId = $(if ($id -match '^\d{6,12}$') { $id } else { $null })
    # last progress line of the setup jobs (the transcript's own header / footer lines are skipped)
    log = "$(Get-Content (Join-Path $dir 'remote.log') -ErrorAction SilentlyContinue | Where-Object { $_ -match '^(Downloading|Installing|msiexec|SSH ready|RustDesk|ERROR)' } | Select-Object -Last 1)"
  }
  $script:remoteAt = Get-Date
  return $script:remoteInfo
}
# ---- WireGuard VPN: the portal sends { on, address, privateKey, hubPublicKey, endpoint, allowed, keepalive } ----
# Runs as the WireGuard tunnel service "mdm-vpn" (starts with Windows). Only 10.66.0.0/16 goes through it.
$wgDir = Join-Path $env:ProgramFiles "WireGuard"
$vpnConf = Join-Path $dir "mdm-vpn.conf"
function Update-Vpn($vpn) {
  $svc = Get-Service 'WireGuardTunnel$mdm-vpn' -ErrorAction SilentlyContinue
  if (-not ($vpn -and $vpn.on)) {
    if ($svc) { & "$wgDir\wireguard.exe" /uninstalltunnelservice mdm-vpn | Out-Null; Remove-Item $vpnConf -ErrorAction SilentlyContinue }
    return
  }
  if (-not (Test-Path "$wgDir\wireguard.exe")) {
    if (-not (Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object CommandLine -match 'vpn-setup')) {
      Start-RemoteJob "vpn-setup" @'
'Downloading WireGuard'
[Net.ServicePointManager]::SecurityProtocol = 'Tls12'
$msi = (Invoke-WebRequest -UseBasicParsing https://download.wireguard.com/windows-client/).Content | Select-String -Pattern 'wireguard-amd64-[\d.]+\.msi' -AllMatches |
  ForEach-Object { $_.Matches.Value } | Sort-Object { [version]($_ -replace '^wireguard-amd64-|\.msi$') } | Select-Object -Last 1
Invoke-WebRequest -UseBasicParsing "https://download.wireguard.com/windows-client/$msi" -OutFile "$env:TEMP\$msi"
'Installing WireGuard ' + $msi
$p = Start-Process msiexec.exe -ArgumentList "/i `"$env:TEMP\$msi`" /qn DO_NOT_LAUNCH=1" -PassThru; $null = $p.Handle; $p.WaitForExit()
'msiexec exit ' + $p.ExitCode
'@
    }
    return
  }
  $text = "[Interface]`r`nPrivateKey = $($vpn.privateKey)`r`nAddress = $($vpn.address)`r`n`r`n[Peer]`r`nPublicKey = $($vpn.hubPublicKey)`r`nEndpoint = $($vpn.endpoint)`r`nAllowedIPs = $($vpn.allowed)`r`nPersistentKeepalive = $($vpn.keepalive)`r`n"
  if ($svc -and $svc.Status -eq "Running" -and (Get-Content $vpnConf -Raw -ErrorAction SilentlyContinue) -eq $text) { return }
  Set-Content -Path $vpnConf -Value $text -Encoding ASCII -NoNewline
  if ($svc) { & "$wgDir\wireguard.exe" /uninstalltunnelservice mdm-vpn | Out-Null; Start-Sleep 3 }
  & "$wgDir\wireguard.exe" /installtunnelservice $vpnConf | Out-Null
}
# Up = the hub answered within the last 3 minutes (WireGuard handshakes every 2 minutes while traffic / keepalives flow).
function Get-VpnUp {
  if (-not (Get-Service 'WireGuardTunnel$mdm-vpn' -ErrorAction SilentlyContinue)) { return $false }
  $hs = "$(& "$wgDir\wg.exe" show mdm-vpn latest-handshakes 2>$null)" -split "\s+" | Select-Object -Last 1
  return [bool]($hs -match '^\d+$' -and [int64]$hs -gt 0 -and ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [int64]$hs) -lt 180)
}

# Long jobs (Windows feature install, RustDesk download) run on their own; their progress goes to remote.log.
function Start-RemoteJob($name, $script) {
  $f = Join-Path $dir "$name.ps1"
  Set-Content -Path $f -Encoding UTF8 -Value "Start-Transcript -Append '$dir\remote.log' | Out-Null`ntry {`n$script`n} catch { 'ERROR: ' + `$_ }`nStop-Transcript | Out-Null"
  Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$f`"" -WindowStyle Hidden
  $script:remoteAt = [datetime]::MinValue
}
# SSH from anywhere: while the portal has SSH on (and the SSH server runs), keep a reverse tunnel from this PC's port 22
# to the portal's SSH jump host, the same way the tablets do.
function Update-Tunnel($ssh) {
  $on = $ssh -and $ssh.on -and (Get-Service sshd -ErrorAction SilentlyContinue).Status -eq "Running"
  if ($script:tunnel -and ($script:tunnel.HasExited -or -not $on)) { if (-not $script:tunnel.HasExited) { $script:tunnel.Kill() }; $script:tunnel = $null }
  if (-not $on -or $script:tunnel) { return }
  $ask = Join-Path $dir "askpass.cmd"
  Set-Content -Path $ask -Value "@echo $($ssh.pass)" -Encoding ASCII
  $env:SSH_ASKPASS = $ask; $env:SSH_ASKPASS_REQUIRE = "force"; $env:DISPLAY = "none"
  $sshExe = @("$env:ProgramFiles\OpenSSH\ssh.exe", "$env:WINDIR\System32\OpenSSH\ssh.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
  $script:tunnel = Start-Process $sshExe -WindowStyle Hidden -PassThru -ArgumentList @(
    "-N", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=`"$dir\known_hosts`"", "-o", "ServerAliveInterval=30", "-o", "ExitOnForwardFailure=yes",
    "-p", "$($ssh.port)", "-R", "$($ssh.remotePort):localhost:22", "$($ssh.user)@$($ssh.host)")
}

function Invoke-Command2($cmd) {
  switch ($cmd.type) {
    "reboot" { Send-Result $cmd $true "Restarting"; Restart-Computer -Force }
    "shutdown" { Send-Result $cmd $true "Shutting down"; Stop-Computer -Force }
    "lockscreen" {
      if (-not (Get-SignedInUser)) { return Send-Result $cmd $false "Nobody is signed in" }
      $e = Invoke-AsUser "rundll32.exe user32.dll,LockWorkStation" 40
      Send-Result $cmd (-not $e) $(if ($e) { $e } else { "Locked" })
    }
    "logoff" {
      if (-not (Get-SignedInUser)) { return Send-Result $cmd $false "Nobody is signed in" }
      $e = Invoke-AsUser "shutdown.exe /l /f" 40
      Send-Result $cmd (-not $e) $(if ($e) { $e } else { "Logged off" })
    }
    "message" {
      if (-not (Get-SignedInUser)) { return Send-Result $cmd $false "Nobody is signed in" }
      Set-Content -Path (Join-Path $pub "mdm-message.txt") -Value $cmd.text -Encoding UTF8
      # no waiting: the message stays until the user clicks OK
      Invoke-AsUser "(New-Object -ComObject WScript.Shell).Popup((Get-Content '$pub\mdm-message.txt' -Raw), 0, 'Message from IT', 64) | Out-Null" 0 "MDM message" | Out-Null
      Send-Result $cmd $true "Shown to $(Get-SignedInUser)"
    }
    "screenshot" {
      $shot = Join-Path $pub "mdm-shot.jpg"
      Remove-Item $shot -ErrorAction SilentlyContinue
      $e = ""
      if (Get-SignedInUser) {
        $e = Invoke-AsUser @"
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type 'using System.Runtime.InteropServices; public class Dpi { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[Dpi]::SetProcessDPIAware() | Out-Null
`$b = [System.Windows.Forms.SystemInformation]::VirtualScreen
`$bmp = New-Object System.Drawing.Bitmap `$b.Width, `$b.Height
[System.Drawing.Graphics]::FromImage(`$bmp).CopyFromScreen(`$b.Left, `$b.Top, 0, 0, `$bmp.Size)
`$bmp.Save('$shot', [System.Drawing.Imaging.ImageFormat]::Jpeg)
"@ 20
      }
      if (Test-Path $shot) {
        Invoke-RestMethod -DisableKeepAlive -Method Post -Uri "$Server/api/screenshot" -Headers $headers -ContentType "image/jpeg" -InFile $shot | Out-Null
      } else {
        $why = if (-not (Get-SignedInUser)) { "Nobody is signed in (the login screen can't be captured)" } elseif ($e) { "Couldn't capture the screen: $e" } else { "Couldn't capture the screen" }
        Invoke-RestMethod -DisableKeepAlive -Method Post -Uri "$Server/api/screenshot?error=$([uri]::EscapeDataString($why))" -Headers $headers | Out-Null
      }
    }
    "run" {
      # the portal's command, as SYSTEM, with a 2-minute limit
      $f = Join-Path $dir "run.ps1"; $out = Join-Path $dir "run-out.txt"; $err = Join-Path $dir "run-err.txt"
      Set-Content -Path $f -Value $cmd.script -Encoding UTF8
      $p = Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$f`"" -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
      $null = $p.Handle # PowerShell 5: without this ExitCode stays empty
      $done = $p.WaitForExit(120000)
      if (-not $done) { $p.Kill() }
      $text = ((Get-Content $out -Raw -ErrorAction SilentlyContinue) + (Get-Content $err -Raw -ErrorAction SilentlyContinue)).Trim()
      if (-not $done) { $text += "`n(stopped after 2 minutes)" }
      Send-Result $cmd ($done -and $p.ExitCode -eq 0) $text
    }
    "file" {
      $dl = Join-Path $dir "downloads"
      New-Item $dl -ItemType Directory -Force | Out-Null
      $name = ($cmd.name -replace '[\\/:*?"<>|]', "_")
      $to = Join-Path $dl $name
      Invoke-WebRequest -DisableKeepAlive -Uri "$Server$($cmd.url)" -Headers $headers -OutFile $to -UseBasicParsing
      if ($cmd.sha256 -and (Get-FileHash $to -Algorithm SHA256).Hash -ne $cmd.sha256.ToUpper()) { Remove-Item $to; return Send-Result $cmd $false "Download damaged (checksum)" }
      if (-not $cmd.run) { return Send-Result $cmd $true "Saved to $to" }
      # installer: .msi silently, anything else with the given arguments; up to 15 minutes
      $p = if ($to -match '\.msi$') { Start-Process msiexec.exe -ArgumentList "/i `"$to`" /qn /norestart" -PassThru }
           elseif ($cmd.args) { Start-Process $to -ArgumentList $cmd.args -PassThru } else { Start-Process $to -PassThru }
      $null = $p.Handle # PowerShell 5: without this ExitCode stays empty
      if (-not $p.WaitForExit(900000)) { return Send-Result $cmd $false "Still running after 15 minutes" }
      Send-Result $cmd ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) "Installer finished, exit code $($p.ExitCode)$(if ($p.ExitCode -eq 3010) { ' (restart needed)' })"
    }
    "sshon" {
      # Turns on an installed OpenSSH: registers the service if Windows lost it (a Windows feature install and the MSI can
      # fight over the "sshd" name), and allows port 22 on every network (Windows' own rule is Private networks only).
      $enableSshd = @'
if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) {
  $exe = @("$env:WINDIR\System32\OpenSSH\sshd.exe", "$env:ProgramFiles\OpenSSH\sshd.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
  if ($exe) { New-Service -Name sshd -BinaryPathName "`"$exe`"" -DisplayName 'OpenSSH SSH Server' -StartupType Automatic | Out-Null }
}
Set-Service sshd -StartupType Automatic; Start-Service sshd
if (-not (Get-NetFirewallRule -Name MDM-SSH-In -ErrorAction SilentlyContinue)) { New-NetFirewallRule -Name MDM-SSH-In -DisplayName 'SSH (device portal)' -Enabled True -Direction Inbound -Protocol TCP -LocalPort 22 -Action Allow -Profile Any | Out-Null }
Enable-NetFirewallRule -Name MDM-SSH-In
New-ItemProperty -Path HKLM:\SOFTWARE\OpenSSH -Name DefaultShell -Value C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -PropertyType String -Force | Out-Null
'@
      $haveFiles = (Test-Path "$env:WINDIR\System32\OpenSSH\sshd.exe") -or (Test-Path "$env:ProgramFiles\OpenSSH\sshd.exe")
      # a half-done Windows optional-feature install holds the "sshd" name until the PC restarts
      if (-not (Get-Service sshd -ErrorAction SilentlyContinue) -and (Get-WindowsCapability -Online -Name OpenSSH.Server* -ErrorAction SilentlyContinue).State -eq "InstallPending") {
        return Send-Result $cmd $false "Windows has an OpenSSH install waiting for a restart. Restart this PC, then press SSH on again."
      }
      if (-not $haveFiles) {
        Start-RemoteJob "ssh-setup" (@'
'Downloading OpenSSH (Microsoft Win32-OpenSSH; the Windows optional feature can hang for hours on Windows Update)'
[Net.ServicePointManager]::SecurityProtocol = 'Tls12'
$rel = Invoke-RestMethod -UseBasicParsing https://api.github.com/repos/PowerShell/Win32-OpenSSH/releases/latest
$asset = $rel.assets | Where-Object { $_.name -match '^OpenSSH-Win64-v[\d.]+\.msi$' } | Select-Object -First 1
$msi = Join-Path $env:TEMP $asset.name
Invoke-WebRequest -UseBasicParsing $asset.browser_download_url -OutFile $msi
'Installing OpenSSH ' + $rel.tag_name
$p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /qn /norestart" -PassThru; $null = $p.Handle; $p.WaitForExit()
'msiexec exit ' + $p.ExitCode
'@ + "`n" + $enableSshd + "`n'SSH ready'")
        return Send-Result $cmd $true "Installing OpenSSH Server in the background (about a minute). The device page shows SSH as on when it is ready."
      }
      Invoke-Expression $enableSshd
      $script:remoteAt = [datetime]::MinValue
      Send-Result $cmd $true "SSH is on: ssh $((Get-RemoteInfo).sshUser)@$ip (the Windows password of that account)"
    }
    "sshoff" {
      try { Stop-Service sshd -Force -ErrorAction Stop; Set-Service sshd -StartupType Disabled -ErrorAction Stop } catch { if (Get-Service sshd -ErrorAction SilentlyContinue) { throw } }
      $script:remoteAt = [datetime]::MinValue
      Send-Result $cmd $true "SSH is off"
    }
    "remote" {
      # RustDesk (free remote desktop): latest release installed silently as a service, with the portal's password
      Start-RemoteJob "remote-setup" (@'
if (-not (Test-Path '__RD__')) {
  'Downloading RustDesk'
  [Net.ServicePointManager]::SecurityProtocol = 'Tls12'
  $rel = Invoke-RestMethod -UseBasicParsing https://api.github.com/repos/rustdesk/rustdesk/releases/latest
  $asset = $rel.assets | Where-Object { $_.name -match '^rustdesk-[\d.]+-x86_64\.exe$' } | Select-Object -First 1
  $exe = Join-Path '__DIR__' $asset.name
  Invoke-WebRequest -UseBasicParsing $asset.browser_download_url -OutFile $exe
  'Installing RustDesk ' + $rel.tag_name
  Start-Process $exe -ArgumentList '--silent-install' # no -Wait: the installer starts RustDesk and never returns
  for ($i = 0; $i -lt 90 -and -not (Test-Path '__RD__'); $i++) { Start-Sleep 2 }
  Start-Sleep 15
}
& '__RD__' --password '__PW__' | Out-Null
Start-Sleep 3
'RustDesk ready, ID ' + (& '__RD__' --get-id | Out-String).Trim()
'@).Replace("__RD__", $rustdesk).Replace("__DIR__", $dir).Replace("__PW__", $cmd.password)
      Send-Result $cmd $true "Setting up RustDesk in the background (download + install, a few minutes). The device page shows the RustDesk ID when it is ready."
    }
    "logs" {
      # recent errors and warnings from the System and Application logs
      $ev = Get-WinEvent -FilterHashtable @{ LogName = "System", "Application"; Level = 1, 2, 3 } -MaxEvents 300 -ErrorAction SilentlyContinue |
        ForEach-Object { "{0:yyyy-MM-dd HH:mm:ss} {1,-11} {2}: {3}" -f $_.TimeCreated, $_.LevelDisplayName, $_.ProviderName, ($_.Message -split "`n")[0] }
      Invoke-RestMethod -DisableKeepAlive -Method Post -Uri "$Server/api/device-logs" -Headers $headers -ContentType "text/plain; charset=utf-8" `
        -Body ([Text.Encoding]::UTF8.GetBytes(($ev -join "`n"))) | Out-Null
    }
  }
}

while ($true) {
  try {
    $os = Get-CimInstance Win32_OperatingSystem
    $battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1
    $disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    # the adapter with the default gateway = the real LAN (not WSL / Hyper-V / VPN virtual adapters)
    $net = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq "Up" } | Select-Object -First 1
    $ip = $net.IPv4Address.IPAddress
    if (-not $ip) { $ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.PrefixOrigin -ne "WellKnown" } | Select-Object -First 1).IPAddress }
    $body = @{
      id = $id; type = "windows"; deviceName = $env:COMPUTERNAME; model = "$($cs.Manufacturer) $($cs.Model)"
      serial = $serial; os = $os.Caption; localIp = $ip; agentVersion = $Version
      uptime = [int]((Get-Date) - $os.LastBootUpTime).TotalSeconds
      cpu = [int](Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
      ram = [int](100 * ($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / $os.TotalVisibleMemorySize)
      disk = [int](100 * ($disk.Size - $disk.FreeSpace) / $disk.Size)
      info = @{
        "Free disk C:" = "{0:N1} GB" -f ($disk.FreeSpace / 1GB)
        "Free memory" = "{0:N1} GB" -f ($os.FreePhysicalMemory / 1MB)
        "User" = "$(Get-SignedInUser)"
        "Agent" = "v$Version"
      }
      remote = (Get-RemoteInfo)
    }
    # device details shown on the device page: the fixed ones read once at start, these on every check-in
    foreach ($k in $details.Keys) { $body.info[$k] = $details[$k] }
    $body.info["Disks"] = (Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object { "{0} {1:N1} of {2:N1} GB free" -f $_.DeviceID, ($_.FreeSpace / 1GB), ($_.Size / 1GB) }) -join " | "
    $body.info["Last boot"] = $os.LastBootUpTime.ToString("yyyy-MM-dd HH:mm")
    if ($net) {
      $body.info["MAC"] = "$($net.NetAdapter.MacAddress)"
      $body.info["Network"] = "$($net.InterfaceAlias) ($($net.NetAdapter.InterfaceDescription), $($net.NetAdapter.LinkSpeed))"
    }
    if ($battery) { $body.battery = [int]$battery.EstimatedChargeRemaining; $body.charging = $battery.BatteryStatus -eq 2 }
    # temperature: the hottest ACPI thermal zone (tenths of a kelvin), readable without admin rights
    $tz = Get-CimInstance Win32_PerfFormattedData_Counters_ThermalZoneInformation -ErrorAction SilentlyContinue | Where-Object HighPrecisionTemperature -gt 2732 | Measure-Object HighPrecisionTemperature -Maximum
    if ($tz.Count) { $body.temp = [math]::Round($tz.Maximum / 10 - 273.15, 1); $body.info["Temperature sensor"] = "ACPI thermal zone" }
    try { $body.vpnUp = Get-VpnUp } catch { }
    $reply = Invoke-RestMethod -DisableKeepAlive -Method Post -Uri "$Server/api/report" -Headers @{ "X-Device-Key" = $Key } `
      -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 3)))
    if ($reply.agentUpdate) {
      # newer agent on the server: replace this file, start the new one with the same settings, and stop this one
      Invoke-WebRequest -DisableKeepAlive -Uri "$Server/download/agent.ps1" -Headers $headers -OutFile "$PSCommandPath.new" -UseBasicParsing
      Move-Item "$PSCommandPath.new" $PSCommandPath -Force
      Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`" -Server $Server -Key $Key -EverySeconds $EverySeconds" -WindowStyle Hidden
      exit 0
    }
    try { Update-Tunnel $reply.ssh } catch { Write-Warning "tunnel: $_" }
    try { Update-Vpn $reply.vpn } catch { Write-Warning "vpn: $_" }
    if ($reply.command) {
      try { Invoke-Command2 $reply.command }
      catch { try { Send-Result $reply.command $false "$_" } catch { } }
    }
  } catch {
    Write-Warning "check-in failed: $_"
  }
  Start-Sleep -Seconds $EverySeconds
}
