param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$')]
    [string]$Repository,
    [string]$OutputDirectory = 'dist/wiki',
    [string]$SourceRevision = 'HEAD',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = Join-Path $projectRoot 'docs/wiki'
$destination = [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
$rootPrefix = $projectRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$pathComparison = if ([IO.Path]::DirectorySeparatorChar -eq '\') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
if (-not $destination.StartsWith($rootPrefix, $pathComparison)) { throw 'Wiki export must stay inside this project, for example .local/wiki or dist/wiki.' }
if ([IO.Path]::GetRelativePath($projectRoot, $destination) -notmatch '^(?:dist|\.local)[\\/]') { throw 'Choose a destination under dist/ or .local/ so generated Wiki output is ignored by Git.' }

function Assert-ProjectPath([string]$Path) {
    if (-not $Path.StartsWith($rootPrefix, $pathComparison)) { throw "Link or destination escapes the project: $Path" }
    # Reject links/junctions along the path, so a lexical in-project path cannot escape it.
    for ($current = $Path; -not $current.Equals($projectRoot, $pathComparison); $current = [IO.Path]::GetDirectoryName($current)) {
        if (Test-Path -LiteralPath $current) {
            if ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Symbolic links and junctions are not supported in Wiki export paths: $current"
            }
        }
    }
}

function ConvertTo-UrlPath([string]$Path) {
    return (($Path.Replace('\', '/') -split '/' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/')
}

Assert-ProjectPath $destination
$resolvedRevision = $null
if ($Repository) {
    $revisionOutput = @(& git -C $projectRoot rev-parse --verify --end-of-options "$SourceRevision^{commit}" 2>&1)
    if ($LASTEXITCODE -ne 0 -or $revisionOutput.Count -ne 1 -or "$($revisionOutput[0])" -notmatch '^(?:[0-9a-f]{40}|[0-9a-f]{64})$') {
        throw "SourceRevision does not resolve to a Git commit: $SourceRevision"
    }
    $resolvedRevision = "$($revisionOutput[0])"
}
$pages = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.md' -File)
if ($pages.Count -eq 0) { throw 'No Wiki pages found.' }
foreach ($page in $pages) {
    Assert-ProjectPath $page.FullName
    Assert-ProjectPath (Join-Path $destination $page.Name)
    if (-not $Force -and (Test-Path -LiteralPath (Join-Path $destination $page.Name))) { throw 'Export would overwrite a page. Review the destination, then use -Force to update these pages.' }
}
$checkedGitPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)

function Convert-LinkTarget([string]$Target, [string]$PagePath) {
    if ($Target -match '^[A-Za-z]:[\\/]') { throw "Absolute file link is not supported in $PagePath`: $Target" }
    if ($Target -match '^(?:[A-Za-z][A-Za-z0-9+.-]*:|//|#|\?)' -or $Target.Length -eq 0) { return $Target }
    $parts = [regex]::Match($Target, '^(?<path>[^?#]*)(?<suffix>[?#].*)?$')
    $linkPath = [Uri]::UnescapeDataString($parts.Groups['path'].Value).Replace('\', '/')
    if ([IO.Path]::IsPathRooted($linkPath) -or $linkPath -match '^[A-Za-z]:') { throw "Absolute file link is not supported in $PagePath`: $Target" }
    $targetPath = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetDirectoryName($PagePath)) $linkPath))
    Assert-ProjectPath $targetPath
    if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) { throw "Unknown Wiki page or missing file in $PagePath`: $Target" }
    $suffix = $parts.Groups['suffix'].Value
    $wikiPage = $pages | Where-Object { $_.FullName.Equals($targetPath, $pathComparison) } | Select-Object -First 1
    if ($wikiPage) {
        if ($Repository) { return "https://github.com/$Repository/wiki/$(ConvertTo-UrlPath $wikiPage.BaseName)$suffix" }
        return "$(ConvertTo-UrlPath $wikiPage.Name)$suffix"
    }
    $repositoryPath = [IO.Path]::GetRelativePath($projectRoot, $targetPath).Replace('\', '/')
    if ($Repository) {
        if ($checkedGitPaths.Add($repositoryPath)) {
            $objectType = @(& git -C $projectRoot cat-file -t "$resolvedRevision`:$repositoryPath" 2>&1)
            if ($LASTEXITCODE -ne 0 -or $objectType.Count -ne 1 -or "$($objectType[0])" -ne 'blob') {
                throw "Linked file is absent from SourceRevision $resolvedRevision`: $repositoryPath"
            }
        }
        return "https://github.com/$Repository/blob/$resolvedRevision/$(ConvertTo-UrlPath $repositoryPath)$suffix"
    }
    return "$(ConvertTo-UrlPath ([IO.Path]::GetRelativePath($destination, $targetPath)))$suffix"
}

function Convert-ProseLinks([string]$Text, [string]$PagePath) {
    # Inline destinations, with optional angle brackets and Markdown link titles.
    # Matching from ]( also permits labels that contain inline code.
    $linkPattern = '\]\(\s*(?:<(?<angle>[^<>\r\n]+)>|(?<bare>[^()\r\n]*?))(?<title>\s+(?:"[^"\r\n]*"|''[^''\r\n]*''|\([^()\r\n]*\)))?\s*\)'
    $rewrite = {
        param($link)
        $targetGroup = if ($link.Groups['angle'].Success) { $link.Groups['angle'] } else { $link.Groups['bare'] }
        $replacement = Convert-LinkTarget $targetGroup.Value $PagePath
        $offset = $targetGroup.Index - $link.Index
        return $link.Value.Substring(0, $offset) + $replacement + $link.Value.Substring($offset + $targetGroup.Length)
    }
    # Code spans may cross lines; their contents must remain literal examples.
    $codePattern = '(?s)(?<!`)(`+)(?!`).*?(?<!`)\1(?!`)'
    $result = [Text.StringBuilder]::new()
    $offset = 0
    foreach ($code in [regex]::Matches($Text, $codePattern)) {
        [void]$result.Append([regex]::Replace($Text.Substring($offset, $code.Index - $offset), $linkPattern, $rewrite))
        [void]$result.Append($code.Value)
        $offset = $code.Index + $code.Length
    }
    [void]$result.Append([regex]::Replace($Text.Substring($offset), $linkPattern, $rewrite))
    return $result.ToString()
}

function Convert-WikiBody([string]$Body, [string]$PagePath) {
    $result = [Text.StringBuilder]::new()
    $prose = [Text.StringBuilder]::new()
    $fence = $null
    foreach ($line in [regex]::Matches($Body, '[^\r\n]*(?:\r\n|\n|\r|$)')) {
        if ($fence) {
            [void]$result.Append($line.Value)
            $closing = '^ {0,3}' + [regex]::Escape($fence.Substring(0, 1)) + '{' + $fence.Length + ',}[ \t]*(?:\r\n|\n|\r|$)$'
            if ($line.Value -match $closing) { $fence = $null }
        } elseif ($line.Value -match '^ {0,3}(`{3,}|~{3,})') {
            $fence = $Matches[1]
            [void]$result.Append((Convert-ProseLinks $prose.ToString() $PagePath))
            [void]$prose.Clear()
            [void]$result.Append($line.Value)
        } elseif ($line.Value -match '^(?: {4}|\t)') {
            [void]$result.Append((Convert-ProseLinks $prose.ToString() $PagePath))
            [void]$prose.Clear()
            [void]$result.Append($line.Value)
        } else {
            [void]$prose.Append($line.Value)
        }
    }
    [void]$result.Append((Convert-ProseLinks $prose.ToString() $PagePath))
    return $result.ToString()
}

# Resolve every link before creating or replacing any output page.
$renderedPages = @{}
foreach ($page in $pages) {
    $renderedPages[$page.Name] = Convert-WikiBody ([IO.File]::ReadAllText($page.FullName)) $page.FullName
}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
foreach ($page in $pages) {
    [IO.File]::WriteAllText((Join-Path $destination $page.Name), $renderedPages[$page.Name], [Text.UTF8Encoding]::new($false))
}
Write-Host "Exported $($pages.Count) Wiki pages to $destination. No Git changes or network writes were performed."
if ($Repository) { Write-Host "Main-repository links are pinned to commit $resolvedRevision." }
if (-not $Repository) { Write-Host 'Local preview only: add -Repository owner/repo when exporting for GitHub to generate absolute Wiki links.' }
