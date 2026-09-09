# Current-source facts only. Historical release/evidence pages are deliberately excluded.
function Find-CurrentDocIssues {
    param($Metadata, [hashtable]$Documents)
    $issues = [Collections.Generic.List[string]]::new()
    foreach ($path in @('README.md', 'docs/FAQ.md', 'docs/BUILDING.md', 'docs/PRIVACY.md')) {
        if ([string]::IsNullOrWhiteSpace($Documents[$path])) {
            $issues.Add("Current documentation is missing or empty: $path")
        }
    }
    $version = [regex]::Escape($Metadata.VersionName)
    $expectedRow = '\|\s*v' + $version + '\s*\|\s*' + $Metadata.VersionCode + '\s*\|\s*' + $Metadata.ProtocolVersion + '\s*\|\s*' + $Metadata.ServiceGeneration + '\s*\|'
    if ($Documents['docs/BUILDING.md'] -and $Documents['docs/BUILDING.md'] -notmatch $expectedRow) {
        $issues.Add('Current build identity row differs from source version/code/protocol/generation.')
    }
    foreach ($path in @('docs/PRIVACY.md')) {
        if ([string]::IsNullOrWhiteSpace($Documents[$path])) { continue }
        $paragraphs = [regex]::Split($Documents[$path].Trim(), '\r?\n\s*\r?\n')
        if ($paragraphs.Count -lt 2 -or $paragraphs[1] -notmatch ('v' + $version + '(?!\d|\.\d)')) {
            $issues.Add("Current privacy introduction has stale version: $path")
        }
    }
    return $issues.ToArray()
}

if ($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference = 'Stop'
    $projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    . (Join-Path $PSScriptRoot 'build-common.ps1')
    $metadata = Get-ProjectMetadata -ProjectRoot $projectRoot
    $documents = @{}
    foreach ($path in @('README.md', 'docs/FAQ.md', 'docs/BUILDING.md', 'docs/PRIVACY.md')) {
        $fullPath = Join-Path $projectRoot $path
        $documents[$path] = if (Test-Path -LiteralPath $fullPath -PathType Leaf) { [IO.File]::ReadAllText($fullPath) } else { '' }
    }
    $issues = @(Find-CurrentDocIssues -Metadata $metadata -Documents $documents)
    if ($issues.Count) { throw ($issues -join "`n") }
    Write-Host "PASS: current documentation exists; build version/code/protocol/generation and privacy introduction match v$($metadata.VersionName)."
    Write-Host 'These checks cover the named version fields; they do not replace a full content review or verify remote metadata.'
}
