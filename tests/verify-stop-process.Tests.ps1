# Runs only harmless local PowerShell children through the production process
# runner. The production workflow is never loaded/executed and adb is never run.
$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '../scripts/verify-stop.ps1'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Production script parse failed.' }
$definitions = $ast.EndBlock.Statements | Where-Object {
    $_ -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $_.Name -in @('ConvertTo-NativeArgument', 'Invoke-AdbProcess')
}
if ($definitions.Count -ne 2) { throw 'Missing production native-process boundary.' }
. ([scriptblock]::Create(($definitions | ForEach-Object { $_.Extent.Text }) -join "`n"))
$adb = (Get-Command powershell.exe -CommandType Application).Source

$timer = [Diagnostics.Stopwatch]::StartNew()
$timedOut = $false
try {
    Invoke-AdbProcess -Arguments @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 30') -TimeoutMilliseconds 250 | Out-Null
} catch { $timedOut = $_.Exception.Message -match 'timed out' }
if (-not $timedOut -or $timer.ElapsedMilliseconds -gt 4000) { throw 'RED: child-process wait was not bounded.' }
Write-Host 'PASS: hung local child is terminated within the deadline plus bounded cleanup.'

$result = Invoke-AdbProcess -Arguments @('-NoProfile', '-NonInteractive', '-Command', '[Console]::Out.Write(("x" * 131072)); [Console]::Error.Write(("y" * 131072)); exit 7') -TimeoutMilliseconds 5000
if ($result.ExitCode -ne 7 -or $result.Output.Length -ne 262144) { throw 'RED: dual-pipe draining or exit-code propagation failed.' }
Write-Host 'PASS: stdout/stderr larger than pipe buffers drain without deadlock; exit code preserved.'

$values = @('plain', 'space value', 'C:\space path\', 'embedded"quote', 'slash\"quote', 'two\\"quote', '', 'a&b|c', '中文')
$result = Invoke-AdbProcess -Arguments (@('-NoProfile', '-NonInteractive', '-File', (Join-Path $PSScriptRoot 'fixtures/echo-arguments.ps1')) + $values) -TimeoutMilliseconds 5000
$decoded = ConvertFrom-Json -InputObject $result.Output
if ($result.ExitCode -ne 0 -or $decoded.Count -ne $values.Count) { throw 'RED: native argument roundtrip failed.' }
for ($i = 0; $i -lt $values.Count; $i++) {
    if ($decoded[$i] -cne $values[$i]) { throw "RED: native argument roundtrip failed at index $i." }
}
Write-Host 'PASS: spaces, quotes, trailing backslashes, empty strings and non-ASCII arguments survive without a shell.'
Write-Host 'GREEN: 3 native-process boundary cases; no real ADB or device used.'
