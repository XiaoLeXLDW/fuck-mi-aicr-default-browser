$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$layoutPath = Join-Path $projectRoot "app\src\main\res\layout\activity_main.xml"
$dimensPath = Join-Path $projectRoot "app\src\main\res\values\dimens.xml"
$androidNamespace = "http://schemas.android.com/apk/res/android"

if (-not (Test-Path -LiteralPath $layoutPath)) {
    throw "UI check RED: missing activity_main.xml"
}
if (-not (Test-Path -LiteralPath $dimensPath)) {
    throw "UI check RED: missing dimens.xml with the fixed top safe margin"
}

[xml]$layout = Get-Content -LiteralPath $layoutPath -Raw
[xml]$dimens = Get-Content -LiteralPath $dimensPath -Raw

$content = $layout.DocumentElement.SelectSingleNode("./LinearLayout")
if ($null -eq $content) {
    throw "UI check RED: missing the page content container"
}

$topPadding = $layout.DocumentElement.GetAttribute("paddingTop", $androidNamespace)
if ($topPadding -ne "@dimen/page_top_safe_margin") {
    throw "UI check RED: the fixed scroll viewport paddingTop must use @dimen/page_top_safe_margin; found '$topPadding'"
}

$clipToPadding = $layout.DocumentElement.GetAttribute("clipToPadding", $androidNamespace)
if ($clipToPadding -ne "true") {
    throw "UI check RED: clipToPadding must stay true so content cannot scroll beneath the status bar"
}

$topMarginNode = $dimens.resources.dimen | Where-Object { $_.name -eq "page_top_safe_margin" }
if ($null -eq $topMarginNode -or $topMarginNode.'#text' -ne "72dp") {
    throw "UI check RED: page_top_safe_margin must remain the requested fixed 72dp"
}

$requiredIds = @(
    "backend_spinner",
    "browser_picker",
    "observe_switch",
    "enable_button",
    "disable_button",
    "refresh_button",
    "status_text",
    "event_text"
)
$presentIds = @()
foreach ($node in $layout.SelectNodes("//*")) {
    $id = $node.GetAttribute("id", $androidNamespace)
    if ($id -match '^@\+id/(.+)$') {
        $presentIds += $Matches[1]
    }
}
$missingIds = $requiredIds | Where-Object { $_ -notin $presentIds }
if ($missingIds.Count -gt 0) {
    throw "UI check RED: required view IDs were removed: $($missingIds -join ', ')"
}

$fillViewport = $layout.DocumentElement.GetAttribute("fillViewport", $androidNamespace)
if ($fillViewport -ne "true") {
    throw "UI check RED: the page must remain vertically scrollable with fillViewport=true"
}

Write-Host "UI check GREEN: fixed 72dp viewport margin, clipped scrolling, and all controller view IDs are intact."
