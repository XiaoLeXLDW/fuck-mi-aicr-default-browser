$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $projectRoot 'scripts/verify-current-docs.ps1')
$metadata = [pscustomobject]@{VersionName='9.8.7';VersionCode='123';ProtocolVersion='200';ServiceGeneration='98700'}
$docs = @{
    'docs/SHIZUKU_COMPATIBILITY.md' = '| v9.8.7 | 123 | 200 | 98700 | local |'
    'docs/PRIVACY.md' = "# Privacy`n`nCurrent v9.8.7.`n`nHistorical v0.3.1 remains valid."
    'docs/wiki/Privacy.md' = "# Privacy`n`nCurrent v9.8.7."
}
if (@(Find-CurrentDocIssues $metadata $docs).Count) { throw 'Current facts plus old history should pass.' }
$docs['docs/SHIZUKU_COMPATIBILITY.md'] = '| v9.8.7 | 123 | 200 | 30002 | local |'
if (@(Find-CurrentDocIssues $metadata $docs).Count -ne 1) { throw 'Stale service generation was not rejected.' }
$docs['docs/PRIVACY.md'] = "# Privacy`n`nCurrent v0.3.1.`n`nSome later text mentions v9.8.7."
if (@(Find-CurrentDocIssues $metadata $docs).Count -ne 2) { throw 'A later mention must not hide a stale introduction.' }
Write-Host 'GREEN: 3 documentation checks (current, stale generation, stale introduction); historical text untouched.'
