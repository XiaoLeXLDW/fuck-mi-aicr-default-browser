# Current-source facts only. Historical release/evidence pages are deliberately excluded.
function Find-CurrentDocIssues {
    param($Metadata, [hashtable]$Documents)
    $issues = [Collections.Generic.List[string]]::new()
    $version = [regex]::Escape($Metadata.VersionName)
    $expectedRow = '\|\s*v' + $version + '\s*\|\s*' + $Metadata.VersionCode + '\s*\|\s*' + $Metadata.ProtocolVersion + '\s*\|\s*' + $Metadata.ServiceGeneration + '\s*\|'
    if ($Documents['docs/SHIZUKU_COMPATIBILITY.md'] -notmatch $expectedRow) {
        $issues.Add('Current compatibility row differs from source version/code/protocol/generation.')
    }
    foreach ($path in @('docs/PRIVACY.md', 'docs/wiki/Privacy.md')) {
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
    foreach ($path in @('docs/SHIZUKU_COMPATIBILITY.md','docs/PRIVACY.md','docs/wiki/Privacy.md')) {
        $documents[$path] = [IO.File]::ReadAllText((Join-Path $projectRoot $path))
    }
    $issues = @(Find-CurrentDocIssues -Metadata $metadata -Documents $documents)
    if ($issues.Count) { throw ($issues -join "`n") }
    Write-Host "PASS: current compatibility and privacy facts match v$($metadata.VersionName); history was not rewritten."
}
