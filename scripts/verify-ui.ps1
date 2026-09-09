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
    "diagnostics_toggle",
    "diagnostics_text",
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

$switch = $layout.SelectSingleNode("//*[local-name()='Switch']")
if ($null -eq $switch -or $switch.GetAttribute('contentDescription', $androidNamespace) -ne '@string/observe_only') {
    throw 'UI check RED: the observation switch must have an accessible name.'
}
$diagnostics = $layout.SelectSingleNode("//*[@*[local-name()='id']='@+id/diagnostics_text']")
if ($diagnostics.GetAttribute('visibility', $androidNamespace) -ne 'gone') {
    throw 'UI check RED: diagnostic details must be collapsed initially.'
}
foreach ($visibleId in @('status_text', 'event_text')) {
    $visibleNode = $layout.SelectSingleNode("//*[@*[local-name()='id']='@+id/$visibleId']")
    while ($null -ne $visibleNode -and $visibleNode -is [System.Xml.XmlElement]) {
        if ($visibleNode.GetAttribute('visibility', $androidNamespace) -in @('gone', 'invisible')) {
            throw "UI check RED: $visibleId must remain visible outside diagnostic details."
        }
        $visibleNode = $visibleNode.ParentNode
    }
}
function Get-Luminance([string]$hex) {
    $rgb = $hex.TrimStart('#')
    if ($rgb.Length -eq 8) { $rgb = $rgb.Substring(2) }
    $channels = foreach ($offset in @(0, 2, 4)) {
        $channel = [Convert]::ToInt32($rgb.Substring($offset, 2), 16) / 255.0
        if ($channel -le 0.04045) { $channel / 12.92 }
        else { [Math]::Pow(($channel + 0.055) / 1.055, 2.4) }
    }
    return 0.2126 * $channels[0] + 0.7152 * $channels[1] + 0.0722 * $channels[2]
}

foreach ($mode in @('values', 'values-night')) {
    [xml]$palette = Get-Content -LiteralPath (Join-Path $projectRoot "app/src/main/res/$mode/colors.xml") -Raw
    [xml]$themes = Get-Content -LiteralPath (Join-Path $projectRoot "app/src/main/res/$mode/styles.xml") -Raw
    $colors = @{}
    foreach ($color in $palette.resources.color) { $colors[$color.name] = $color.InnerText }
    $light = if ($mode -eq 'values') { 'true' } else { 'false' }
    $parent = if ($mode -eq 'values') { 'android:style/Theme.Material.Light' } else { 'android:style/Theme.Material' }
    foreach ($theme in @('AppTheme', 'BrowserPickerDialog')) {
        $style = $themes.SelectSingleNode("/resources/style[@name='$theme']")
        $suffix = if ($theme -eq 'AppTheme') { '.NoActionBar' } else { '.Dialog.Alert' }
        if ($null -eq $style -or $style.parent -ne "$parent$suffix") {
            throw "UI check RED: $mode $theme must use the matching native Material theme."
        }
    }
    foreach ($bar in @('windowLightStatusBar', 'windowLightNavigationBar')) {
        if ($themes.SelectSingleNode("/resources/style[@name='AppTheme']/item[@name='android:$bar']").InnerText -ne $light) {
            throw "UI check RED: $mode $bar must be $light."
        }
    }
    $pairs = @('ink/bg', 'ink/surface', 'ink/surface_subtle', 'muted/bg', 'muted/surface',
        'muted/surface_subtle', 'accent_dark/accent_soft', 'accent_dark/line', 'on_accent/accent', 'on_accent/accent_pressed')
    foreach ($pair in $pairs) {
        $names = $pair.Split('/')
        $a = Get-Luminance $colors[$names[0]]
        $b = Get-Luminance $colors[$names[1]]
        $contrast = ([Math]::Max($a, $b) + 0.05) / ([Math]::Min($a, $b) + 0.05)
        if ($contrast -lt 4.5) { throw "UI check RED: $mode $pair text contrast $contrast is below 4.5:1." }
    }
}
$primaryButton = $layout.SelectSingleNode("//*[@*[local-name()='id']='@+id/enable_button']")
if ($primaryButton.GetAttribute('textColor', $androidNamespace) -ne '@color/on_accent') {
    throw 'UI check RED: primary button text must adapt with on_accent.'
}
[xml]$manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'app/src/main/AndroidManifest.xml') -Raw
$activity = $manifest.SelectSingleNode("/manifest/application/activity[@*[local-name()='name']='.MainActivity']")
if ($activity.GetAttribute('configChanges', $androidNamespace).Split('|') -contains 'uiMode') {
    throw 'UI check RED: let Android recreate MainActivity when system dark mode changes.'
}
Write-Host 'UI check GREEN: 72dp safe margin, accessible controls, collapsed diagnostics, day/night native themes, system bar icons and text contrast are intact.'
Write-Host 'Static checks only; live theme switching, dialogs, rotation, system insets, large fonts and TalkBack still require device validation.'
