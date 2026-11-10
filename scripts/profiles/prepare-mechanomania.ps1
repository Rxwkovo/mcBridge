param(
    [Parameter(Mandatory)] [string] $Source,
    [Parameter(Mandatory)] [string] $Launcher
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$sourceInstance = Join-Path $project 'integration-test\instance'
$client = Join-Path $project 'integration-test\production-client'
$game = Join-Path $client '.minecraft'
$versionDir = Join-Path $game 'versions\Mechanomania'
$baseDir = Join-Path $game 'versions\1.21.1'
foreach ($path in @($versionDir,$baseDir,(Join-Path $game 'libraries'),(Join-Path $game 'assets\indexes'),(Join-Path $game 'assets\objects'))) { New-Item -ItemType Directory -Force -Path $path | Out-Null }
Copy-Item -LiteralPath $Launcher -Destination (Join-Path $client 'HMCL.exe') -Force
foreach ($version in @('Mechanomania','1.21.1')) {
    foreach ($ext in @('json','jar')) {
        $relative = "versions\$version\$version.$ext"
        Copy-Item -LiteralPath (Join-Path $source $relative) -Destination (Join-Path $game $relative) -Force
    }
}
foreach ($name in @('mods','config','defaultconfigs','kubejs')) {
    $from = Join-Path $sourceInstance $name
    if (Test-Path -LiteralPath $from) {
        $to = Join-Path $versionDir $name
        New-Item -ItemType Directory -Force -Path $to | Out-Null
        Get-ChildItem -LiteralPath $from -Force | Where-Object {
            $name -ne 'config' -or $_.Name -notin @('numen_api-common.toml','resourceful-config-web.json','mcpfabric.config.json')
        } | Copy-Item -Destination $to -Recurse -Force
    }
}
$bridge = Join-Path $project 'versions\1.21.1-neoforge\build\libs\mcpfabric-neoforge-0.9.1+1.21.1.jar'
if (!(Test-Path -LiteralPath $bridge -PathType Leaf)) { throw 'Current bridge JAR not found.' }
Copy-Item -LiteralPath $bridge -Destination (Join-Path $versionDir 'mods\mcpfabric-neoforge-0.9.1+1.21.1.jar') -Force
# New per-version settings, with no copied launcher account or user-defined launch commands.
@{ usesGlobal=$false; gameDirType=1; java='Custom'; javaDir=(Join-Path $project '.gradle-user\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe'); autoMemory=$true; maxMemory=8192 } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $versionDir 'hmclversion.cfg') -Encoding utf8
# NeoForge discovers generated game and loader artifacts by convention; they are not
# listed as ordinary downloads in the version manifest. Copy only this exact version.
foreach ($runtime in @(
    'net\neoforged\neoforge\21.1.248\neoforge-21.1.248-client.jar',
    'net\neoforged\neoforge\21.1.248\neoforge-21.1.248-universal.jar',
    'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar',
    'net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-extra.jar'
)) {
    $from = Join-Path $source "libraries\$runtime"
    if (!(Test-Path -LiteralPath $from -PathType Leaf)) { throw "Missing NeoForge client runtime: $runtime" }
    $to = Join-Path $game "libraries\$runtime"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $to) | Out-Null
    Copy-Item -LiteralPath $from -Destination $to -Force
}
# These files contain credential-like keys; do not transfer them into the new launcher profile.
foreach ($name in @('numen_api-common.toml','resourceful-config-web.json','mcpfabric.config.json')) {
    $file = Join-Path $versionDir "config\$name"
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
}
$config = Join-Path $versionDir 'config\mcpfabric.config.json'
@{ host='127.0.0.1'; port=25604; requireAuth=$true; writeDiscoveryFile=$false; enableWorldWrite=$false; enableCommands=$false; enablePlayerControl=$false; enableSupervisedControl=$false; enableUiControl=$false; enableVision=$true; enableRegistryRead=$true } | ConvertTo-Json | Set-Content -LiteralPath $config -Encoding utf8
# Copy only local artifacts referenced by the two version manifests, not the original launcher account state.
$missing = [System.Collections.Generic.List[string]]::new()
foreach ($version in @('Mechanomania','1.21.1')) {
    $manifest = Get-Content -LiteralPath (Join-Path $game "versions\$version\$version.json") -Raw | ConvertFrom-Json
    foreach ($lib in $manifest.libraries) {
        $paths = @()
        if ($lib.downloads.artifact.path) { $paths += $lib.downloads.artifact.path }
        if ($lib.downloads.classifiers) { $paths += @($lib.downloads.classifiers.PSObject.Properties.Value | ForEach-Object path) }
        foreach ($relative in $paths) {
            $from = Join-Path $source "libraries\$relative"
            if (!(Test-Path -LiteralPath $from -PathType Leaf)) { $missing.Add($relative); continue }
            $to = Join-Path $game "libraries\$relative"
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $to) | Out-Null
            Copy-Item -LiteralPath $from -Destination $to -Force
        }
    }
}
Copy-Item -LiteralPath (Join-Path $source 'assets\indexes\17.json') -Destination (Join-Path $game 'assets\indexes\17.json') -Force
$assetIndex = Get-Content -LiteralPath (Join-Path $game 'assets\indexes\17.json') -Raw | ConvertFrom-Json
foreach ($asset in $assetIndex.objects.PSObject.Properties.Value) {
    $prefix = $asset.hash.Substring(0,2)
    $from = Join-Path $source "assets\objects\$prefix\$($asset.hash)"
    if (!(Test-Path -LiteralPath $from -PathType Leaf)) { throw 'A required asset object is missing from the original installation.' }
    $dir = Join-Path $game "assets\objects\$prefix"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $to = Join-Path $dir $asset.hash
    if (!(Test-Path -LiteralPath $to)) { Copy-Item -LiteralPath $from -Destination $to }
}
Write-Host "Prepared isolated launcher files at $client; no save or launcher account copied."
Write-Host "Unavailable library artifacts: $($missing.Count) (some may be for non-Windows platforms)."
Write-Host 'Launch only after configuring HMCL to use this isolated .minecraft root and its own credentials.'
