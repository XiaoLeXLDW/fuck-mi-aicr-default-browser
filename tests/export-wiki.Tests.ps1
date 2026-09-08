#requires -Version 7.0
# Offline integration tests invoke the real exporter in an isolated Git fixture.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$testRoot = Join-Path $projectRoot ('.local/wiki-export-tests-' + [Guid]::NewGuid().ToString('N'))
$fixtureRoot = Join-Path $testRoot 'repo'
$powerShell = (Get-Process -Id $PID).Path
$testCount = 0
$exportCount = 0
New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'scripts'), (Join-Path $fixtureRoot 'docs/wiki') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts/export-wiki.ps1') -Destination (Join-Path $fixtureRoot 'scripts/export-wiki.ps1')

function Write-Fixture([string]$Path, [string]$Body) {
    [IO.File]::WriteAllText((Join-Path $fixtureRoot $Path), $Body, [Text.UTF8Encoding]::new($false))
}
function Invoke-FixtureGit([string[]]$Arguments) {
    $output = @(& git -C $fixtureRoot -c user.name=WikiExportTests -c user.email=wiki-export@example.invalid -c commit.gpgsign=false -c core.hooksPath=.no-hooks @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Fixture git failed: $($output -join "`n")" }
    return $output
}
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-Contains([string]$Body, [string]$Expected) {
    Assert-True ($Body.Contains($Expected)) "Expected exported text: $Expected"
}
function Invoke-Export([string[]]$Options = @(), [string]$ExpectedError, [string]$OutputDirectory) {
    $script:exportCount++
    if (-not $OutputDirectory) { $OutputDirectory = ".local/export-$script:exportCount" }
    $output = @(& $powerShell -NoLogo -NoProfile -File (Join-Path $fixtureRoot 'scripts/export-wiki.ps1') -OutputDirectory $OutputDirectory @Options 2>&1)
    $exitCode = $LASTEXITCODE
    if ($ExpectedError) {
        Assert-True ($exitCode -ne 0) "Expected export failure: $ExpectedError"
        Assert-True (($output -join "`n").Contains($ExpectedError)) "Wrong failure: $($output -join "`n")"
    } else {
        Assert-True ($exitCode -eq 0) "Export failed: $($output -join "`n")"
    }
    return Join-Path $fixtureRoot $OutputDirectory
}
function Test-Case([string]$Name, [scriptblock]$Action) {
    & $Action
    $script:testCount++
    Write-Host "PASS $script:testCount`: $Name"
}

$originalArchitecture = @'
# 架构
[v0.3.3 修复记录](../FIX_REVIEW_0.3.3.md)
[首页](Home.md#start)
[`代码标签`](./Home.md?view=1#part)
[中文页面](<中文 页面.md> "页面标题")
[说明](<../中文 说明.md?view=1#检查> "保留标题")
[空格](../notes with spaces.md 'title')
[fragment](#local) [query](?view=1#local)
[external](https://example.invalid/Unknown.md#part) [mail](mailto:hello@example.invalid)
[protocol-relative](//example.invalid/Missing.md)
`[inline example](Missing-inline.md)`
``[example with ` backtick](Missing-double.md)``
`[multiline example]
(Missing-multiline.md)`
```markdown
[fenced example](Missing-fenced.md)
```
~~~~markdown
[tilde example](Missing-tilde.md)
~~~
[still fenced](Missing-still-fenced.md)
~~~~
    [indented example](Missing-indented.md)
'@
Write-Fixture 'docs/wiki/Architecture.md' $originalArchitecture
Write-Fixture 'docs/wiki/Home.md' '# Home'
Write-Fixture 'docs/wiki/中文 页面.md' '# 中文页面'
Write-Fixture 'docs/FIX_REVIEW_0.3.3.md' '# Fix review'
Write-Fixture 'docs/中文 说明.md' '# 中文说明'
Write-Fixture 'docs/notes with spaces.md' '# Notes'
Invoke-FixtureGit @('-c', 'init.defaultBranch=main', 'init', '--quiet') | Out-Null
Invoke-FixtureGit @('add', 'docs') | Out-Null
Invoke-FixtureGit @('commit', '--quiet', '-m', 'Fixture baseline') | Out-Null
$firstRevision = "$(Invoke-FixtureGit @('rev-parse', 'HEAD'))"
Write-Fixture 'docs/new-only.md' '# New document'
Invoke-FixtureGit @('add', 'docs/new-only.md') | Out-Null
Invoke-FixtureGit @('commit', '--quiet', '-m', 'Fixture second commit') | Out-Null
$headRevision = "$(Invoke-FixtureGit @('rev-parse', 'HEAD'))"
$githubOptions = @('-Repository', 'example/wiki-project')
$githubExport = Invoke-Export $githubOptions
$githubBody = [IO.File]::ReadAllText((Join-Path $githubExport 'Architecture.md'))

Test-Case 'cross-directory review link points to the complete HEAD commit' {
    Assert-Contains $githubBody "[v0.3.3 修复记录](https://github.com/example/wiki-project/blob/$headRevision/docs/FIX_REVIEW_0.3.3.md)"
}
Test-Case 'sibling Wiki links retain fragments, queries and code labels' {
    Assert-Contains $githubBody '[首页](https://github.com/example/wiki-project/wiki/Home#start)'
    Assert-Contains $githubBody '[`代码标签`](https://github.com/example/wiki-project/wiki/Home?view=1#part)'
    Assert-Contains $githubBody '[中文页面](<https://github.com/example/wiki-project/wiki/%E4%B8%AD%E6%96%87%20%E9%A1%B5%E9%9D%A2> "页面标题")'
}
Test-Case 'Chinese names and spaces are encoded without changing titles or suffixes' {
    Assert-Contains $githubBody "[说明](<https://github.com/example/wiki-project/blob/$headRevision/docs/%E4%B8%AD%E6%96%87%20%E8%AF%B4%E6%98%8E.md?view=1#检查> `"保留标题`")"
    Assert-Contains $githubBody "[空格](https://github.com/example/wiki-project/blob/$headRevision/docs/notes%20with%20spaces.md 'title')"
}
Test-Case 'external and page-local links stay unchanged' {
    Assert-Contains $githubBody '[fragment](#local) [query](?view=1#local)'
    Assert-Contains $githubBody '[external](https://example.invalid/Unknown.md#part) [mail](mailto:hello@example.invalid)'
    Assert-Contains $githubBody '[protocol-relative](//example.invalid/Missing.md)'
}
Test-Case 'inline, fenced, tilde and indented code examples stay byte-for-byte unchanged' {
    $codeStart = $originalArchitecture.IndexOf('`[inline example]')
    Assert-Contains $githubBody $originalArchitecture.Substring($codeStart)
}
Test-Case 'preview links resolve to real source files relative to the export directory' {
    $previewExport = Invoke-Export
    $previewBody = [IO.File]::ReadAllText((Join-Path $previewExport 'Architecture.md'))
    Assert-Contains $previewBody '[首页](Home.md#start)'
    Assert-Contains $previewBody '[v0.3.3 修复记录](../../docs/FIX_REVIEW_0.3.3.md)'
    Assert-Contains $previewBody '[说明](<../../docs/%E4%B8%AD%E6%96%87%20%E8%AF%B4%E6%98%8E.md?view=1#检查> "保留标题")'
    foreach ($target in @('Home.md', '../../docs/FIX_REVIEW_0.3.3.md', '../../docs/中文 说明.md', '../../docs/notes with spaces.md')) {
        Assert-True (Test-Path -LiteralPath (Join-Path $previewExport $target) -PathType Leaf) "Broken preview target: $target"
    }
}
Test-Case 'SourceRevision resolves an older revision to its full commit ID' {
    $explicitExport = Invoke-Export ($githubOptions + @('-SourceRevision', 'HEAD^'))
    $explicitBody = [IO.File]::ReadAllText((Join-Path $explicitExport 'Architecture.md'))
    Assert-Contains $explicitBody "/blob/$firstRevision/docs/FIX_REVIEW_0.3.3.md"
    Assert-True (-not $explicitBody.Contains("/blob/$headRevision/")) 'Explicit revision was ignored.'
}
Test-Case 'invalid SourceRevision fails without creating output' {
    $failedExport = Invoke-Export ($githubOptions + @('-SourceRevision', 'missing-ref')) 'SourceRevision does not resolve to a Git commit'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Invalid revision created output.'
}
Test-Case 'SourceRevision must identify a commit, not a blob' {
    $failedExport = Invoke-Export ($githubOptions + @('-SourceRevision', 'HEAD:docs/FIX_REVIEW_0.3.3.md')) 'SourceRevision does not resolve to a Git commit'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Non-commit revision created output.'
}
Test-Case 'missing Wiki page fails before any pages are written' {
    Write-Fixture 'docs/wiki/Architecture.md' '[missing](No-Such-Page.md)'
    $failedExport = Invoke-Export $githubOptions 'Unknown Wiki page or missing file'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Missing Wiki page created output.'
}
Test-Case 'missing cross-directory file also fails in local preview' {
    Write-Fixture 'docs/wiki/Architecture.md' '[missing](../missing.md)'
    $failedExport = Invoke-Export -ExpectedError 'Unknown Wiki page or missing file'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Missing preview target created output.'
}
Test-Case 'links escaping the repository are rejected even if the target exists' {
    [IO.File]::WriteAllText((Join-Path $testRoot 'outside.md'), '# Outside fixture repository')
    Write-Fixture 'docs/wiki/Architecture.md' '[outside](../../../outside.md)'
    $failedExport = Invoke-Export $githubOptions 'escapes the project'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Escaping link created output.'
}
Test-Case 'percent-encoded traversal cannot bypass the repository boundary' {
    Write-Fixture 'docs/wiki/Architecture.md' '[outside](%2e%2e/%2e%2e/%2e%2e/outside.md)'
    $failedExport = Invoke-Export -ExpectedError 'escapes the project'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Encoded traversal created output.'
}
Test-Case 'working-tree-only file cannot become a nonexistent GitHub blob link' {
    Write-Fixture 'docs/untracked.md' '# Untracked'
    Write-Fixture 'docs/wiki/Architecture.md' '[untracked](../untracked.md)'
    $failedExport = Invoke-Export $githubOptions 'Linked file is absent from SourceRevision'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Untracked Git target created output.'
}
Test-Case 'linked files must exist at the explicitly selected commit' {
    Write-Fixture 'docs/wiki/Architecture.md' '[new](../new-only.md)'
    $failedExport = Invoke-Export ($githubOptions + @('-SourceRevision', $firstRevision)) 'Linked file is absent from SourceRevision'
    Assert-True (-not (Test-Path -LiteralPath $failedExport)) 'Wrong-revision Git target created output.'
}
Test-Case 'Force validates the whole export before replacing existing output' {
    Write-Fixture 'docs/wiki/Architecture.md' $originalArchitecture
    Write-Fixture 'docs/wiki/ZZ-Broken.md' '[missing](../missing.md)'
    $before = [IO.File]::ReadAllText((Join-Path $githubExport 'Architecture.md'))
    $relativeOutput = [IO.Path]::GetRelativePath($fixtureRoot, $githubExport)
    $null = Invoke-Export ($githubOptions + @('-Force')) 'Unknown Wiki page or missing file' $relativeOutput
    Assert-True ([IO.File]::ReadAllText((Join-Path $githubExport 'Architecture.md')) -ceq $before) 'Failed export overwrote a valid page.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $githubExport 'ZZ-Broken.md'))) 'Failed export wrote part of the page set.'
    Write-Fixture 'docs/wiki/ZZ-Broken.md' '# Repaired fixture'
}
Test-Case 'existing pages still require explicit Force to overwrite' {
    $relativeOutput = [IO.Path]::GetRelativePath($fixtureRoot, $githubExport)
    $null = Invoke-Export $githubOptions 'Export would overwrite a page' $relativeOutput
}
Write-Host "GREEN: $testCount Wiki export checks; real-script fixtures retained at $testRoot. No network or device access."
