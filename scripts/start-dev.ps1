# Windows local launcher. Credentials stay in the child environment, never argv/logs.
[CmdletBinding()]
param(
    [ValidateSet('Start', 'Stop', 'Status', 'Restart')][string]$Action = 'Start',
    [switch]$Background,
    [switch]$SkipBuild,
    [switch]$PreviewFrontend,
    [switch]$Offline,
    [ValidateRange(1, 65535)][int]$BackendPort = 8080,
    [ValidateRange(1, 65535)][int]$FrontendPort = 5173,
    [string]$JavaHome,
    [string]$MavenHome
)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectDir '.runtime'
$recordFile = Join-Path $runtimeDir 'dev-windows.json'
$jarFile = Join-Path $projectDir 'backend\target\douyinVirtualLive-1.0.0.jar'
$viteFile = Join-Path $projectDir 'frontend\node_modules\vite\bin\vite.js'
$savedEnvironment = @{}

function Set-ChildEnvironment([string]$Name, [string]$Value) {
    if (-not $savedEnvironment.ContainsKey($Name)) {
        $savedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Get-Record {
    if (Test-Path -LiteralPath $recordFile) { return Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json }
    return $null
}
function Get-OwnedProcess($Entry) {
    if ($null -eq $Entry -or $Entry.pid -lt 2) { return $null }
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$($Entry.pid)" -ErrorAction SilentlyContinue
    if ($null -eq $candidate) { return $null }
    $sameCreation = $candidate.CreationDate.ToUniversalTime().Ticks -eq ([DateTime]$Entry.createdAt).ToUniversalTime().Ticks
    if (-not $sameCreation -or $candidate.ExecutablePath -ine $Entry.executable -or
        -not $candidate.CommandLine -or $candidate.CommandLine.IndexOf($Entry.marker, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        return $null
    }
    return $candidate
}
function Get-Listeners([int]$Port) {
    return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}
function Test-Health([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch { return $false }
}
function Show-Status {
    $record = Get-Record
    $back = $BackendPort; $front = $FrontendPort
    if ($record) { $back = [int]$record.backendPort; $front = [int]$record.frontendPort }
    $backendOwned = $null -ne (Get-OwnedProcess $record.backend)
    $frontendOwned = $null -ne (Get-OwnedProcess $record.frontend)
    [pscustomobject]@{
        backendPort = $back; frontendPort = $front
        backendPid = $(if ($backendOwned) { $record.backend.pid } else { $null })
        frontendPid = $(if ($frontendOwned) { $record.frontend.pid } else { $null })
        backendManaged = $backendOwned; frontendManaged = $frontendOwned
        frontendMode = $(if ($record.frontendMode) { $record.frontendMode } else { 'dev' })
        backendReady = Test-Health "http://127.0.0.1:$back/api/health"
        frontendReady = Test-Health "http://127.0.0.1:$front/api/health"
        backendListeners = @((Get-Listeners $back) | Select-Object -ExpandProperty OwningProcess -Unique)
        frontendListeners = @((Get-Listeners $front) | Select-Object -ExpandProperty OwningProcess -Unique)
        controlUrl = "http://127.0.0.1:$front/control"; liveUrl = "http://127.0.0.1:$front/live"
        logs = $runtimeDir
    } | ConvertTo-Json -Depth 4
}
function Stop-Project {
    $record = Get-Record
    if (-not $record) { Write-Host 'No recorded Windows services; no processes were stopped.'; return }
    foreach ($role in @('frontend', 'backend')) {
        $entry = $record.$role
        if (-not $entry) { continue }
        $owned = Get-OwnedProcess $entry
        if (-not $owned) {
            if (Get-Process -Id $entry.pid -ErrorAction SilentlyContinue) {
                throw "PID $($entry.pid) no longer matches this project record; refusing to stop it."
            }
            continue
        }
        if ($role -eq 'backend') {
            # Ask Java to close its worker/login bridge before terminating the root.
            foreach ($platform in @('douyin', 'bilibili')) {
                try { Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($record.backendPort)/api/$platform/disconnect" -ContentType 'application/json' -Body '{}' -TimeoutSec 3 | Out-Null } catch {}
            }
        }
        # Only descendants of the verified, creation-time-matched root belong to this launch.
        $all = @(Get-CimInstance Win32_Process)
        $pending = @([int]$entry.pid); $descendants = @()
        while ($pending.Count -gt 0) {
            $next = @($all | Where-Object { $_.ParentProcessId -in $pending -and $_.CreationDate -ge $owned.CreationDate })
            $descendants += $next; $pending = @($next | ForEach-Object { [int]$_.ProcessId })
        }
        foreach ($child in $descendants) {
            $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($child.ProcessId)" -ErrorAction SilentlyContinue
            if ($current -and $current.CreationDate -eq $child.CreationDate) { Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue }
        }
        Stop-Process -Id $entry.pid -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $entry.pid -Timeout 10 -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $recordFile -Force
    Write-Host 'Recorded project services stopped.'
}
function Quote-Argument([string]$Value) {
    if ($Value.Contains('"')) { throw 'A launch path cannot contain a quote.' }
    return '"' + $Value + '"'
}
function Start-ServiceProcess([string]$Role, [string]$Executable, [string[]]$LaunchArguments, [string]$WorkingDir, [string]$Marker) {
    $process = Start-Process -FilePath $Executable -ArgumentList $LaunchArguments -WorkingDirectory $WorkingDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir "$Role.log") -RedirectStandardError (Join-Path $runtimeDir "$Role.err.log")
    $details = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
    return @{ pid = $process.Id; createdAt = $details.CreationDate.ToUniversalTime().ToString('o'); executable = $details.ExecutablePath; marker = $Marker }
}
function Invoke-Build([string]$WorkingDir, [string]$Executable, [string[]]$BuildArguments) {
    Push-Location -LiteralPath $WorkingDir
    try { & $Executable @BuildArguments; if ($LASTEXITCODE -ne 0) { throw "Build failed in $WorkingDir (exit $LASTEXITCODE)." } }
    finally { Pop-Location }
}
function Save-Record($Record) {
    $temporary = $recordFile + '.tmp'
    $Record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $recordFile -Force
}
function Start-Project {
    $existing = Get-Record
    if ($existing -and ((Get-OwnedProcess $existing.backend) -or (Get-OwnedProcess $existing.frontend))) {
        if ($existing.backendPort -ne $BackendPort -or $existing.frontendPort -ne $FrontendPort) { throw 'Recorded services use different ports. Stop them first.' }
        if ((Get-OwnedProcess $existing.backend) -and (Get-OwnedProcess $existing.frontend) -and
            (Test-Health "http://127.0.0.1:$BackendPort/api/health") -and (Test-Health "http://127.0.0.1:$FrontendPort/api/health")) { Show-Status; return }
        throw 'A partial project launch exists. Use -Action Restart to stop verified project processes first.'
    }
    foreach ($port in @($BackendPort, $FrontendPort)) {
        $listeners = @(Get-Listeners $port)
        if ($listeners.Count -gt 0) { throw "Port $port is occupied (PID $($listeners[0].OwningProcess)); no existing process was stopped." }
    }
    $envPath = Join-Path $projectDir '.env'
    if (Test-Path -LiteralPath $envPath) {
        foreach ($line in Get-Content -LiteralPath $envPath -Encoding UTF8) {
            if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
                $name = $matches[1]; $value = $matches[2].Trim()
                if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
                Set-ChildEnvironment $name $value
            }
        }
    }
    $toolsDir = Join-Path (Split-Path -Parent $projectDir) 'work\tools'
    if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { $JavaHome = Join-Path $toolsDir 'jdk-21.0.12.1+1' }
    if (-not $MavenHome) { $MavenHome = $env:MAVEN_HOME }
    if (-not $MavenHome -or -not (Test-Path -LiteralPath (Join-Path $MavenHome 'bin\mvn.cmd'))) { $MavenHome = Join-Path $toolsDir 'apache-maven-3.9.11' }
    $java = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { $java = (Get-Command java.exe -ErrorAction Stop).Source; $JavaHome = Split-Path -Parent (Split-Path -Parent $java) }
    $maven = Join-Path $MavenHome 'bin\mvn.cmd'
    if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source }
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $npm = Join-Path (Split-Path -Parent $node) 'node_modules\npm\bin\npm-cli.js'
    if (-not (Test-Path -LiteralPath $npm)) { throw 'npm-cli.js was not found beside Node.js.' }
    Set-ChildEnvironment 'JAVA_HOME' $JavaHome
    Set-ChildEnvironment 'PATH' ((Join-Path $JavaHome 'bin') + ';' + $env:PATH)
    Set-ChildEnvironment 'SERVER_PORT' ([string]$BackendPort)
    Set-ChildEnvironment 'DOUYIN_NODE' $node
    Set-ChildEnvironment 'BILIBILI_NODE' $node
    if ($Offline) {
        Set-ChildEnvironment 'AI_PROVIDER' 'mock'
        Set-ChildEnvironment 'TTS_PROVIDER' 'browser'
        Set-ChildEnvironment 'AI_DAILY_REQUEST_LIMIT' '0'
        Set-ChildEnvironment 'TTS_DAILY_CHAR_LIMIT' '0'
        Set-ChildEnvironment 'PAID_AUTO_ENABLED' 'false'
        Set-ChildEnvironment 'PROVIDER_DATA_DIR' (Join-Path $runtimeDir 'launcher-offline')
    }
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    if (-not $SkipBuild) {
        # Windows locks a running JAR. Refuse to rebuild it even if a fixture owns it.
        if (Test-Path -LiteralPath $jarFile) {
            try { $jarLock = [IO.File]::Open($jarFile, 'Open', 'ReadWrite', 'None'); $jarLock.Dispose() }
            catch { throw 'The backend JAR is in use. Stop its owning project/fixture before building; no process was terminated.' }
        }
        foreach ($module in @('frontend', 'collector')) {
            $moduleDir = Join-Path $projectDir $module
            if (-not (Test-Path -LiteralPath (Join-Path $moduleDir 'node_modules'))) { Invoke-Build $moduleDir $node @($npm, 'ci', '--no-audit', '--no-fund') }
            Invoke-Build $moduleDir $node @($npm, 'run', 'build')
        }
        Invoke-Build (Join-Path $projectDir 'backend') $maven @('-B', '-ntp', '-DskipTests', 'package')
    }
    if (-not (Test-Path -LiteralPath $jarFile) -or -not (Test-Path -LiteralPath $viteFile) -or -not (Test-Path -LiteralPath (Join-Path $projectDir 'collector\dist\worker.js'))) { throw 'Build outputs are missing; rerun without -SkipBuild.' }
    if ($PreviewFrontend -and -not (Test-Path -LiteralPath (Join-Path $projectDir 'frontend\dist\index.html'))) { throw 'The approved frontend build is missing.' }
    $record = @{ project = $projectDir; startedAt = [DateTime]::UtcNow.ToString('o'); backendPort = $BackendPort; frontendPort = $FrontendPort; backend = $null; frontend = $null }
    $record.frontendMode = $(if ($PreviewFrontend) { 'preview' } else { 'dev' })
    try {
        $record.backend = Start-ServiceProcess 'backend' $java @('-jar', (Quote-Argument $jarFile), "--server.port=$BackendPort") (Join-Path $projectDir 'backend') $jarFile
        Save-Record $record
        $frontendArguments = @((Quote-Argument $viteFile))
        if ($PreviewFrontend) { $frontendArguments += 'preview' }
        $frontendArguments += @('--host', '127.0.0.1', '--port', [string]$FrontendPort, '--strictPort')
        $record.frontend = Start-ServiceProcess 'frontend' $node $frontendArguments (Join-Path $projectDir 'frontend') $viteFile
        Save-Record $record
        $ready = $false
        for ($attempt = 0; $attempt -lt 90; $attempt++) {
            if (-not (Get-OwnedProcess $record.backend) -or -not (Get-OwnedProcess $record.frontend)) { throw 'A service exited. Inspect .runtime/backend*.log and frontend*.log.' }
            $backendOwnsPort = @((Get-Listeners $BackendPort) | Where-Object { $_.OwningProcess -eq $record.backend.pid }).Count -gt 0
            $frontendOwnsPort = @((Get-Listeners $FrontendPort) | Where-Object { $_.OwningProcess -eq $record.frontend.pid }).Count -gt 0
            if ($backendOwnsPort -and $frontendOwnsPort -and (Test-Health "http://127.0.0.1:$BackendPort/api/health") -and (Test-Health "http://127.0.0.1:$FrontendPort/api/health")) { $ready = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw 'Service readiness timed out. Inspect .runtime logs.' }
    } catch { Stop-Project; throw }
    Show-Status
    if (-not $Background) {
        Write-Host 'Services are running. Press Ctrl+C to stop this project; use -Background to detach.'
        try { while ((Get-OwnedProcess $record.backend) -and (Get-OwnedProcess $record.frontend)) { Start-Sleep -Seconds 1 } }
        finally { Stop-Project }
    }
}
try {
    switch ($Action) {
        'Status' { Show-Status }
        'Stop' { Stop-Project }
        'Start' { Start-Project }
        'Restart' { Stop-Project; Start-Project }
    }
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
