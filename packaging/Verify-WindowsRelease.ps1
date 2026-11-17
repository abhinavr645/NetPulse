param([string]$ImagePath, [ValidateSet('Native', 'Runtime')][string]$Launcher = 'Runtime', [switch]$BatchOnly)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($ImagePath)) {
    [xml]$pom = Get-Content -LiteralPath (Join-Path $workspace 'pom.xml')
    $ImagePath = Join-Path $workspace "release/NetPulse-$($pom.project.version)-windows-x64/NetPulse"
}
$ImagePath = [IO.Path]::GetFullPath($ImagePath)
$nativeLauncherPath = Join-Path $ImagePath 'NetPulse.exe'
if (-not (Test-Path -LiteralPath $nativeLauncherPath)) { throw 'Build the Windows application image first.' }
$runRoot = Join-Path $workspace ('target/packaged-verification/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$verificationDataRoot = Join-Path $runRoot 'profile'
New-Item -ItemType Directory -Force -Path $verificationDataRoot | Out-Null
$agentJar = Join-Path $runRoot 'packaged-smoke-agent.jar'
$manifest = Join-Path $runRoot 'agent-manifest.txt'
"Manifest-Version: 1.0`r`nPremain-Class: com.netpulse.ui.PackagedSmokeAgent`r`n`r`n" |
    Set-Content -LiteralPath $manifest -Encoding ASCII
$testClassRoot = Join-Path $workspace 'target/test-classes'
if (-not (Test-Path -LiteralPath (Join-Path $testClassRoot 'com/netpulse/ui/PackagedSmokeAgent.class'))) {
    throw 'Run mvn test to compile the external acceptance agent first.'
}
& jar --create --file $agentJar --manifest $manifest -C $testClassRoot com/netpulse/ui/PackagedSmokeAgent.class
if ($LASTEXITCODE -ne 0) { throw 'Could not create the external verification agent.' }

function Image-Fingerprint {
    @(Get-ChildItem -LiteralPath $ImagePath -Recurse -File | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($ImagePath.Length) + ' ' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }) -join "`n"
}
$before = Image-Fingerprint

function Launch-Checked([string]$stage, [bool]$withAgent) {
    $stageRoot = Join-Path $runRoot $stage
    New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null
    $info = New-Object System.Diagnostics.ProcessStartInfo
    if ($Launcher -eq 'Native') {
        $info.FileName = $nativeLauncherPath
    } else {
        $info.FileName = Join-Path $ImagePath 'runtime/bin/javaw.exe'
        $applicationJar = Get-ChildItem -LiteralPath (Join-Path $ImagePath 'app') -Filter 'netpulse-*.jar' | Select-Object -First 1
        $info.Arguments = '--enable-native-access=ALL-UNNAMED -cp "' + $applicationJar.FullName + '" com.netpulse.DesktopLauncher'
    }
    $info.WorkingDirectory = Join-Path $env:WINDIR 'System32'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($key in @('JAVA_HOME', 'JDK_HOME', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')) {
        $info.EnvironmentVariables.Remove($key)
    }
    $info.EnvironmentVariables['PATH'] = "$env:WINDIR\System32;$env:WINDIR;$env:WINDIR\System32\WindowsPowerShell\v1.0"
    $info.EnvironmentVariables['LOCALAPPDATA'] = $verificationDataRoot
    if ($withAgent) { $info.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = '-javaagent:"' + $agentJar + '"="' + $stageRoot + '"' }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    $started = $false
    try {
        if (-not $process.Start()) { throw 'Packaged launcher did not start.' }
        $started = $true
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $withAgent) {
            $opened = $false
            $deadline = [DateTime]::UtcNow.AddSeconds(25)
            while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
                $process.Refresh()
                if ($process.MainWindowTitle -eq 'NetPulse - Network Bandwidth Dashboard') { $opened = $true; break }
                Start-Sleep -Milliseconds 100
            }
            if (-not $opened) { throw 'Ordinary, uninstrumented launcher did not show its dashboard.' }
            $jvm = @($process.Modules | Where-Object { $_.ModuleName -eq 'jvm.dll' })
            if ($jvm.Count -ne 1 -or -not $jvm[0].FileName.StartsWith($ImagePath + '\runtime\', [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Ordinary launcher did not load its bundled JVM.'
            }
            if (-not $process.CloseMainWindow()) { throw 'Could not close the ordinary GUI normally.' }
        }
        if (-not $process.WaitForExit(90000)) { throw "Packaged $stage check timed out." }
        $process.WaitForExit()
        $stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $stageRoot 'stdout.txt')
        $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $stageRoot 'stderr.txt')
        if ($process.ExitCode -ne 0) {
            $failure = Join-Path $stageRoot 'failure.txt'
            if (Test-Path -LiteralPath $failure) { Get-Content -LiteralPath $failure | Write-Output }
            Get-Content -LiteralPath (Join-Path $stageRoot 'stderr.txt') | Write-Output
            throw "Packaged $stage check exited with $($process.ExitCode). Evidence: $stageRoot"
        }
        if ($withAgent) {
            $result = Join-Path $stageRoot 'result.txt'
            if (-not (Test-Path -LiteralPath $result) -or (Get-Content -LiteralPath $result -Tail 1) -ne 'PASS') {
                throw "Acceptance agent did not report PASS. Evidence: $stageRoot"
            }
            Get-Content -LiteralPath $result | Write-Output
        } else { Write-Output "Ordinary $Launcher launcher: GUI opened and closed without agent, JAVA_HOME or Java/Maven in PATH; bundled jvm.dll confirmed." }
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}

function Launch-BatchChecked {
    $runtimeLauncherPath = Join-Path $ImagePath 'runtime/bin/javaw.exe'
    $previous = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $runtimeLauncherPath })
    if ($previous.Count -ne 0) { throw 'Close other instances of this packaged application before the batch check.' }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = Join-Path $env:WINDIR 'System32/cmd.exe'
    $info.Arguments = '/d /c ""' + (Join-Path $ImagePath 'NetPulse.cmd') + '""'
    $info.WorkingDirectory = Join-Path $env:WINDIR 'System32'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    foreach ($key in @('JAVA_HOME', 'JDK_HOME', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')) {
        $info.EnvironmentVariables.Remove($key)
    }
    $info.EnvironmentVariables['PATH'] = "$env:WINDIR\System32;$env:WINDIR;$env:WINDIR\System32\WindowsPowerShell\v1.0"
    $info.EnvironmentVariables['LOCALAPPDATA'] = $verificationDataRoot
    $command = [Diagnostics.Process]::Start($info)
    $application = $null
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(25)
        while ([DateTime]::UtcNow -lt $deadline) {
            $found = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $runtimeLauncherPath })
            if ($found.Count -eq 1 -and $found[0].MainWindowTitle -eq 'NetPulse - Network Bandwidth Dashboard') {
                $application = $found[0]
                break
            }
            Start-Sleep -Milliseconds 100
        }
        if ($null -eq $application) { throw 'NetPulse.cmd did not launch its packaged dashboard.' }
        # Open and retain the child handle before it exits so ExitCode remains queryable.
        $childHandle = $application.Handle
        if (-not $application.CloseMainWindow()) { throw 'Batch-launched window did not accept normal close.' }
        if (-not $application.WaitForExit(15000)) { throw 'Batch-launched application did not close normally.' }
        if ($application.ExitCode -ne 0) { throw "Batch-launched application exited with $($application.ExitCode)." }
        if (-not $command.WaitForExit(5000) -or $command.ExitCode -ne 0) { throw 'Batch launcher returned an error.' }
        Write-Output 'NetPulse.cmd: actual batch launcher opened and closed the GUI using bundled Java, without an agent or installed Java in PATH.'
    } finally {
        if ($null -ne $application) {
            if (-not $application.HasExited) { $application.Kill(); $application.WaitForExit() }
            $application.Dispose()
        }
        if (-not $command.HasExited) { $command.Kill(); $command.WaitForExit() }
        $command.Dispose()
    }
}

if (-not $BatchOnly) {
    Launch-Checked 'first' $true
    Launch-Checked 'restart' $true
    Launch-Checked 'ordinary' $false
}
Launch-BatchChecked
if ($before -ne (Image-Fingerprint)) { throw 'Packaged application wrote into or modified its application image.' }
$database = Join-Path $verificationDataRoot 'NetPulse/netpulse.db'
if (-not (Test-Path -LiteralPath $database)) { throw 'Per-user SQLite database was not created.' }
$closedCopy = Join-Path $verificationDataRoot 'NetPulse/closed-check.db'
Move-Item -LiteralPath $database -Destination $closedCopy
Move-Item -LiteralPath $closedCopy -Destination $database
Write-Output "PACKAGED ACCEPTANCE PASSED. Image unchanged; SQLite file released. Evidence: $runRoot"
