param([switch]$Lan, [switch]$Once, [int]$Port = 8765)
$ErrorActionPreference = 'Stop'
$arguments = @("$PSScriptRoot/collector/server.mjs", '--port', "$Port")
if ($Lan) { $arguments += '--lan' }
if ($Once) { $arguments += '--once' }
& node @arguments
if ($LASTEXITCODE -ne 0) { throw 'Collector failed' }
