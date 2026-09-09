$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $projectRoot 'scripts/verify-current-docs.ps1')
$metadata = [pscustomobject]@{VersionName='9.8.7';VersionCode='123';ProtocolVersion='200';ServiceGeneration='98700'}
$docs = @{
    'README.md' = '# Project'
    'docs/FAQ.md' = '# FAQ'
    'docs/BUILDING.md' = '| v9.8.7 | 123 | 200 | 98700 | local |'
    'docs/PRIVACY.md' = "# Privacy`n`nCurrent v9.8.7.`n`nHistorical v0.3.1 remains valid."
    'docs/releases/v0.3.1.md' = 'Historical v0.3.1; code 8, protocol 100, generation 30002.'
}
if (@(Find-CurrentDocIssues $metadata $docs).Count) { throw 'Current facts plus old history should pass.' }
foreach ($path in @('README.md', 'docs/FAQ.md', 'docs/BUILDING.md', 'docs/PRIVACY.md')) {
    $missingDoc = $docs.Clone()
    $missingDoc.Remove($path)
    if (@(Find-CurrentDocIssues $metadata $missingDoc).Count -ne 1) { throw "Missing current document was not rejected: $path" }
}
$docs['docs/BUILDING.md'] = '| v9.8.7 | 123 | 200 | 30002 | local |'
if (@(Find-CurrentDocIssues $metadata $docs).Count -ne 1) { throw 'Stale service generation was not rejected.' }
$docs['docs/PRIVACY.md'] = "# Privacy`n`nCurrent v0.3.1.`n`nSome later text mentions v9.8.7."
if (@(Find-CurrentDocIssues $metadata $docs).Count -ne 2) { throw 'A later mention must not hide a stale introduction.' }
Write-Host 'GREEN: 7 documentation checks (current, four missing documents, stale generation, stale introduction); historical releases untouched.'
