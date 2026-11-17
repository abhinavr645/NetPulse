param([switch]$UpdateDocumentationOnly, [switch]$SkipVerifiedTests)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
[xml]$pom = Get-Content -LiteralPath (Join-Path $workspace 'pom.xml')
$version = [string]$pom.project.version
$releaseName = "NetPulse-$version-windows-x64"
$releaseRoot = Join-Path $workspace "release/$releaseName"
$inputRoot = Join-Path $workspace 'target/release-input'
$jdkRoot = Split-Path (Split-Path (Get-Command jpackage.exe -ErrorAction Stop).Source)

function Reset-GeneratedDirectory([string]$directory) {
    $absolute = [IO.Path]::GetFullPath($directory)
    if (-not $absolute.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a directory outside the workspace: $absolute"
    }
    if ($absolute -ne [IO.Path]::GetFullPath($releaseRoot) -and $absolute -ne [IO.Path]::GetFullPath($inputRoot)) {
        throw "Refusing to remove an unexpected directory: $absolute"
    }
    $ancestor = $absolute
    while ($ancestor -ne $workspace) {
        if ((Test-Path -LiteralPath $ancestor) -and
            ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to follow a junction or symbolic link: $ancestor"
        }
        $ancestor = Split-Path $ancestor
    }
    if (Test-Path -LiteralPath $absolute) { Remove-Item -LiteralPath $absolute -Recurse -Force }
    New-Item -ItemType Directory -Path $absolute -Force | Out-Null
}

Push-Location $workspace
try {
    if (-not $UpdateDocumentationOnly) {
        Reset-GeneratedDirectory $inputRoot
        $mavenArguments = @('--batch-mode', '--no-transfer-progress', 'package', '-Pwindows-release')
        if ($SkipVerifiedTests) { $mavenArguments += '-DskipTests' }
        & mvn @mavenArguments
        if ($LASTEXITCODE -ne 0) { throw 'Maven release build failed.' }
        Copy-Item -LiteralPath "target/netpulse-$version.jar" -Destination $inputRoot
        Reset-GeneratedDirectory $releaseRoot
        & (Join-Path $jdkRoot 'bin/jpackage.exe') --type app-image --name NetPulse --app-version $version `
            --input $inputRoot --main-jar "netpulse-$version.jar" --main-class com.netpulse.DesktopLauncher `
            --dest $releaseRoot --icon (Join-Path $PSScriptRoot 'assets/netpulse.ico') `
            --description 'Network Bandwidth Monitoring and Analysis System' --vendor 'NetPulse Project' `
            --add-modules 'java.desktop,java.sql,java.instrument,jdk.crypto.ec' `
            --jlink-options '--strip-debug --no-header-files --no-man-pages' `
            --java-options '--enable-native-access=ALL-UNNAMED'
        if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
        (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'NetPulse.cmd') -Raw).Replace('@APP_JAR@', "netpulse-$version.jar") |
            Set-Content -LiteralPath (Join-Path $releaseRoot 'NetPulse/NetPulse.cmd') -Encoding ASCII
    } elseif (-not (Test-Path -LiteralPath (Join-Path $releaseRoot 'NetPulse/NetPulse.exe'))) {
        throw 'Build the application image before updating its documentation.'
    }

    $docs = Join-Path $releaseRoot 'docs'
    $licenses = Join-Path $releaseRoot 'licenses'
    New-Item -ItemType Directory -Force -Path $docs, $licenses | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'RELEASE_README.md') -Destination (Join-Path $releaseRoot 'README.md')
    Copy-Item -LiteralPath 'docs/QUICK_START.md' -Destination $releaseRoot
    Copy-Item -LiteralPath 'docs/NetPulse_Project_Report.md','docs/ARCHITECTURE.md' -Destination $docs
    if (Test-Path -LiteralPath 'docs/PHASE10_RELEASE_VERIFICATION.md') {
        Copy-Item -LiteralPath 'docs/PHASE10_RELEASE_VERIFICATION.md' -Destination $docs
    }
    Copy-Item -LiteralPath 'docs/screenshots' -Destination $docs -Recurse -Force
    Copy-Item -LiteralPath 'docs/THIRD_PARTY_NOTICES.md' -Destination $licenses
    Copy-Item -LiteralPath 'packaging/licenses/sqlite-jdbc-NOTICE.txt' -Destination $licenses
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jdbc = Get-ChildItem -LiteralPath (Join-Path $releaseRoot 'NetPulse/app') -Filter 'sqlite-jdbc-*.jar' | Select-Object -First 1
    if ($null -eq $jdbc) { throw 'SQLite JDBC was not packaged.' }
    $archive = [IO.Compression.ZipFile]::OpenRead($jdbc.FullName)
    try {
        foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '/LICENSE(?:\.zentus)?$' }) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,
                (Join-Path $licenses ('sqlite-jdbc-' + $entry.Name + '.txt')), $true)
        }
    } finally { $archive.Dispose() }
    # Runtime legal notices remain intact in NetPulse/runtime/legal.
    $unwanted = @(Get-ChildItem -LiteralPath $releaseRoot -Recurse -File | Where-Object {
        $_.Extension -in @('.db', '.pdb', '.log') -or $_.Name -like '*junit*.jar' -or $_.Name -like '*test*.jar'
    })
    if ($unwanted.Count -ne 0) { throw "Unexpected development/user files in release: $($unwanted.Name -join ', ')" }
    $zipPath = Join-Path $workspace "release/$releaseName.zip"
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    [IO.Compression.ZipFile]::CreateFromDirectory($releaseRoot, $zipPath,
        [IO.Compression.CompressionLevel]::Optimal, $true)
    $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $releaseName.zip" | Set-Content -LiteralPath ($zipPath + '.sha256') -Encoding ASCII
    [pscustomobject]@{
        Application = Join-Path $releaseRoot 'NetPulse/NetPulse.exe'
        ApplicationImageBytes = (Get-ChildItem -LiteralPath (Join-Path $releaseRoot 'NetPulse') -Recurse -File | Measure-Object Length -Sum).Sum
        ReleaseBytes = (Get-ChildItem -LiteralPath $releaseRoot -Recurse -File | Measure-Object Length -Sum).Sum
        Zip = $zipPath
        ZipBytes = (Get-Item -LiteralPath $zipPath).Length
        SHA256 = $hash
    } | Format-List
} finally { Pop-Location }
