param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$')]
    [string]$Repository,
    [string]$OutputDirectory = 'dist/wiki',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $projectRoot 'docs/wiki'
$destination = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$rootPrefix = $projectRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $destination.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Wiki export must stay inside this project, for example .local/wiki or dist/wiki.' }
if ($destination -notmatch '[\\/](?:dist|\.local)[\\/]') { throw 'Choose a destination under dist/ or .local/ so generated Wiki output is ignored by Git.' }
$pages = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.md' -File)
if ($pages.Count -eq 0) { throw 'No Wiki pages found.' }
foreach ($page in $pages) {
    if (-not $Force -and (Test-Path -LiteralPath (Join-Path $destination $page.Name))) { throw 'Export would overwrite a page. Review the destination, then use -Force to update these pages.' }
}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$pageNames = @($pages.BaseName)
foreach ($page in $pages) {
    $body = [IO.File]::ReadAllText($page.FullName)
    if ($Repository) {
        $body = [regex]::Replace($body, '\]\(([A-Za-z0-9_-]+)\.md(#[^)]*)?\)', {
            param($match)
            $name = $match.Groups[1].Value
            if ($name -notin $pageNames) { throw "Unknown Wiki page: $name" }
            return '](' + "https://github.com/$Repository/wiki/$name" + $match.Groups[2].Value + ')'
        })
    }
    [IO.File]::WriteAllText((Join-Path $destination $page.Name), $body, [Text.UTF8Encoding]::new($false))
}
Write-Host "Exported $($pages.Count) Wiki pages to $destination. No Git operations or network writes were performed."
if (-not $Repository) { Write-Host 'Local preview only: add -Repository owner/repo when exporting for GitHub to generate absolute Wiki links.' }
