param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$files = @(& git -C $projectRoot -c core.quotepath=false ls-files --cached --others --exclude-standard | Sort-Object -Unique)
if ($LASTEXITCODE -ne 0) { throw 'Run this check inside an initialized Git checkout.' }
if ($files.Count -eq 0) { throw 'No source files found.' }
$problems = [Collections.Generic.List[string]]::new()
$protectedPattern = '(^|/)(keys|dist|\.tools|\.local|\.ci|\.gradle|\.gradle-user-home|\.codex-remote-attachments|build)/|\.(apk|aab|jks|keystore|p12|pfx|pem|key|log|hprof)$|(^|/)(local|keystore|signing)\.properties$|(^|/)\.env($|\.)'
$secretPatterns = @(
    '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '\bgh[pousr]_[A-Za-z0-9]{30,}\b',
    '\bgithub_pat_[A-Za-z0-9_]{40,}\b',
    '\bAKIA[0-9A-Z]{16}\b'
)
$textExtensions = @('.md', '.txt', '.java', '.aidl', '.xml', '.gradle', '.properties', '.ps1', '.yml', '.yaml', '.json')
foreach ($relative in $files) {
    if ($relative -match $protectedPattern -and $relative -notmatch '(^|/)\.env\.example$') { $problems.Add("Private/generated path included: $relative"); continue }
    $path = Join-Path $projectRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $item = Get-Item -LiteralPath $path
    if ($item.Length -gt 1MB) { $problems.Add("Unexpected large source file: $relative") }
    if ($item.Extension -notin $textExtensions) { continue }
    $body = [IO.File]::ReadAllText($path)
    foreach ($pattern in $secretPatterns) {
        if ($body -match $pattern) { $problems.Add("Possible credential in $relative (content not printed)") }
    }
    if ($item.Extension -eq '.ps1') {
        $parseErrors = $null
        [void][Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$parseErrors)
        foreach ($errorItem in $parseErrors) { $problems.Add("PowerShell parse error in ${relative}: $($errorItem.Message)") }
    }
    if ($item.Extension -eq '.md') {
        $withoutCode = [regex]::Replace($body, '(?ms)^```.*?^```[^\r\n]*', '')
        foreach ($link in [regex]::Matches($withoutCode, '\]\(([^)\s]+)(?:\s+"[^"]*")?\)')) {
            $target = $link.Groups[1].Value.Trim('<', '>')
            if ($target -match '^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|#|/)') { continue }
            $target = [Uri]::UnescapeDataString(($target -split '[#?]', 2)[0])
            if ($target -and -not (Test-Path -LiteralPath (Join-Path $item.DirectoryName $target))) { $problems.Add("Broken local link in ${relative}: $target") }
        }
    }
}

$wrapper = Join-Path $projectRoot 'gradle/wrapper/gradle-wrapper.jar'
if (-not (Test-Path -LiteralPath $wrapper)) { $problems.Add('Gradle Wrapper JAR is missing') }
elseif ((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash.ToLowerInvariant() -ne '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17') { $problems.Add('Gradle 8.9 Wrapper JAR checksum mismatch') }
$wrapperProperties = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle/wrapper/gradle-wrapper.properties') -Raw
if ($wrapperProperties -notmatch 'distributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab') { $problems.Add('Gradle 8.9 distribution checksum missing or changed') }

$licensePairs = @{
    'LICENSE' = 'Project-MIT.txt'
    'THIRD_PARTY_NOTICES.md' = 'THIRD_PARTY_NOTICES.txt'
    'licenses/Apache-2.0.txt' = 'Apache-2.0.txt'
    'licenses/MPL-2.0.txt' = 'MPL-2.0.txt'
    'licenses/Shizuku-API-MIT.txt' = 'Shizuku-API-MIT.txt'
}
foreach ($source in $licensePairs.Keys) {
    $target = Join-Path $projectRoot ('app/src/main/assets/licenses/' + $licensePairs[$source])
    if (-not (Test-Path -LiteralPath $target)) { $problems.Add("Packaged license missing: $source"); continue }
    # Compare normalized text so LF/CRLF checkout rules do not cause false failures.
    $sourceText = [IO.File]::ReadAllText((Join-Path $projectRoot $source)).Replace("`r`n", "`n").TrimEnd()
    $targetText = [IO.File]::ReadAllText($target).Replace("`r`n", "`n").TrimEnd()
    if ($sourceText -cne $targetText) { $problems.Add("Packaged license differs from $source") }
}
. (Join-Path $PSScriptRoot 'build-common.ps1')
$metadata = Get-ProjectMetadata -ProjectRoot $projectRoot
if ($problems.Count -gt 0) { $problems | ForEach-Object { Write-Host "FAIL: $_" }; throw "Repository check failed: $($problems.Count) issue(s)." }
Write-Host "PASS: $($files.Count) candidate files; local links, PowerShell syntax, protected paths, common credential patterns, Wrapper and license copies; v$($metadata.VersionName)."
Write-Host 'This checks current candidate files, not Git history, remote CI or real-device behavior.'
