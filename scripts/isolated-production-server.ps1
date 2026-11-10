param(
    [switch] $Start,
    [ValidateSet('Base','Create')] [string] $Profile = 'Create',
    [string[]] $ExtraJvmArgs = @()
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$server = Join-Path $project $(if ($Profile -eq 'Base') {'integration-test\production-server-base'} else {'integration-test\production-server'})
$gamePort = if ($Profile -eq 'Base') {25577} else {25576}
$bridgePort = if ($Profile -eq 'Base') {25605} else {25603}
$instance = Join-Path $project 'integration-test\instance'
$java = Join-Path $project '.gradle-user\jdks\eclipse_adoptium-21-amd64-windows.2\bin\java.exe'
$argsFile = Join-Path $server 'libraries\net\neoforged\neoforge\21.1.248\win_args.txt'
if (!(Test-Path -LiteralPath $java -PathType Leaf) -or !(Test-Path -LiteralPath $argsFile -PathType Leaf)) {
    throw 'Java 21 or the installed NeoForge 21.1.248 server is missing.'
}
if ($Start) {
    # Refuse a second local server before replacing its JAR or opening its world.
    $occupied = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object { $_.Port -in @($gamePort, $bridgePort) }
    if ($occupied) { throw 'An isolated test port is already in use; stop its server before launching another copy.' }
}
foreach ($name in @('mods', 'config', 'tmp')) { New-Item -ItemType Directory -Path (Join-Path $server $name) -Force | Out-Null }
$create = Join-Path $instance 'mods\create-1.21.1-6.0.10.jar'
$bridge = Join-Path $project 'versions\1.21.1-neoforge\build\libs\mcpfabric-neoforge-0.9.1+1.21.1.jar'
if (($Profile -eq 'Create' -and !(Test-Path -LiteralPath $create -PathType Leaf)) -or !(Test-Path -LiteralPath $bridge -PathType Leaf)) {
    throw 'The isolated Create 6.0.10 copy or current bridge JAR is missing.'
}
if ($Profile -eq 'Create') {
    Copy-Item -LiteralPath $create -Destination (Join-Path $server 'mods\create-1.21.1-6.0.10.jar') -Force
} elseif (Get-ChildItem -LiteralPath (Join-Path $server 'mods') -Filter '*.jar' | Where-Object Name -ne 'mcpfabric-neoforge-0.9.1+1.21.1.jar') {
    throw 'Base profile must contain only the Bridge JAR.'
}
Copy-Item -LiteralPath $bridge -Destination (Join-Path $server 'mods\mcpfabric-neoforge-0.9.1+1.21.1.jar') -Force
$properties = Join-Path $server 'server.properties'
if (!(Test-Path -LiteralPath $properties)) {
    @('server-ip=127.0.0.1', "server-port=$gamePort", 'online-mode=true', 'enable-rcon=false', 'enable-query=false', 'enable-command-block=false', 'level-name=mechagent-acceptance', 'level-type=minecraft:flat') | Set-Content -LiteralPath $properties -Encoding ascii
}
$serverProperties = Get-Content -LiteralPath $properties -Raw
if ($serverProperties -notmatch '(?m)^server-ip=127\.0\.0\.1\s*$' -or $serverProperties -notmatch "(?m)^server-port=$gamePort\s*$" -or $serverProperties -notmatch '(?m)^online-mode=true\s*$') {
    throw 'Refusing to start: server.properties must bind loopback and keep online-mode enabled.'
}
$config = Join-Path $server 'config\mcpfabric.config.json'
if (!(Test-Path -LiteralPath $config)) {
    @{ host='127.0.0.1'; port=$bridgePort; requireAuth=$true; writeDiscoveryFile=$false; enableWorldWrite=$false; enableCommands=$false; enablePlayerControl=$false; enableSupervisedControl=$false; enableUiControl=$false; enableVision=$true; enableRegistryRead=$true } | ConvertTo-Json | Set-Content -LiteralPath $config -Encoding utf8
}
$c = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
if ($c.host -ne '127.0.0.1' -or $c.port -ne $bridgePort -or $c.requireAuth -ne $true -or $c.writeDiscoveryFile -ne $false -or $c.enableWorldWrite -ne $false -or $c.enableCommands -ne $false -or $c.enablePlayerControl -ne $false -or $c.enableSupervisedControl -ne $false -or $c.enableUiControl -ne $false) {
    throw 'Refusing to start: bridge config is not restricted to authenticated read-only observation.'
}
if (!$Start) { Write-Host "Prepared isolated server at $server. No EULA accepted and no server started."; return }
$eula = Join-Path $server 'eula.txt'
if (!(Test-Path -LiteralPath $eula) -or (Get-Content -LiteralPath $eula -Raw) -notmatch '(?m)^eula=true\s*$') {
    throw 'The server EULA has not been accepted in this isolated server. This script never accepts it.'
}
Push-Location $server
try {
    & $java '-Xmx4G' "-Duser.home=$server" "-Djava.io.tmpdir=$(Join-Path $server 'tmp')" @ExtraJvmArgs '@user_jvm_args.txt' '@libraries/net/neoforged/neoforge/21.1.248/win_args.txt' 'nogui'
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE" }
} finally { Pop-Location }
