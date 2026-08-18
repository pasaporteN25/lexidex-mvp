[CmdletBinding()]
param(
    [int]$Port = 8765,
    [string]$HostAddress = "127.0.0.1"
)

$ErrorActionPreference = "Stop"

function Test-PortInUse {
    param([string]$Address, [int]$CandidatePort)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connection = $client.BeginConnect($Address, $CandidatePort, $null, $null)
        if (-not $connection.AsyncWaitHandle.WaitOne(250)) {
            return $false
        }
        $client.EndConnect($connection)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$bundledPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
if ($env:LEXIDEX_PYTHON) {
    $python = $env:LEXIDEX_PYTHON
} elseif (Test-Path -LiteralPath $bundledPython) {
    $python = $bundledPython
} else {
    $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $pythonCommand) {
        throw "No se encontro Python. Define LEXIDEX_PYTHON con la ruta a python.exe."
    }
    $python = $pythonCommand.Source
}

$requestedPort = $Port
while (Test-PortInUse -Address $HostAddress -CandidatePort $Port) {
    $Port += 1
    if ($Port -gt $requestedPort + 20) {
        throw "No se encontro un puerto libre entre $requestedPort y $Port."
    }
}

if ($Port -ne $requestedPort) {
    Write-Host "El puerto $requestedPort esta ocupado; Lexidex usara $Port."
}

$url = "http://${HostAddress}:$Port"
Write-Host "Lexidex: $url"
Write-Host "Deja esta terminal abierta y presiona Ctrl+C para detener el servidor."

Push-Location $PSScriptRoot
try {
    & $python -B backend\lexidex_api.py --host $HostAddress --port $Port
} finally {
    Pop-Location
}
