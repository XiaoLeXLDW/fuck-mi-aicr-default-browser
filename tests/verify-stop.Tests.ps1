# Offline only: replays production AST functions + workflow, not a copied model.
# External ADB, signer and time boundaries are injected. No device is accessed.
param([string]$ScriptPath = (Join-Path $PSScriptRoot '../scripts/verify-stop.ps1'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../scripts/build-common.ps1')
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$metadata = Get-ProjectMetadata -ProjectRoot $projectRoot
$source = Get-Content -LiteralPath $ScriptPath -Raw -Encoding UTF8

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function New-Fixture([string]$Scenario = 'BothAlive', [string]$Choice = 'Auto') {
    $fixtureMetadata = $metadata
    $state = @{
        Enabled = $false; EnableTaps = 0; StopTaps = 0; Opens = 0; Now = 0L
        Scenario = $Scenario; Choice = $Choice; Signatures = 0
        StopReported = $false; DiagnosticsExpanded = $false; DiagnosticTaps = 0; AppPackage = $metadata.PackageName
        Page = 0; Swipes = 0; Scrolling = $Scenario -in @('Offscreen', 'DiagnosticsUnreachable', 'StopUnreachable', 'ForeignScroll')
        Commands = [Collections.Generic.List[string]]::new()
        Timeouts = [Collections.Generic.List[int]]::new()
    }
    $transport = {
        param([string[]]$Arguments, [int]$TimeoutMilliseconds)
        $command = $Arguments -join ' '
        $state.Commands.Add($command)
        $state.Timeouts.Add($TimeoutMilliseconds)
        $state.Now += 1
        if ($state.Scenario -eq 'Disconnected' -and $state.EnableTaps -gt 0) {
            return @{ ExitCode = 1; Output = 'private-device https://private.invalid/?key=private-key' }
        }
        if ($state.Scenario -eq 'HungAdb' -and $state.EnableTaps -gt 0) {
            $state.Now += $TimeoutMilliseconds
            throw 'private-device https://private.invalid/?key=private-key'
        }
        if ($command -eq 'devices') {
            $devices = switch ($state.Scenario) {
                'NoDevice' { 'List of devices attached' }
                'MultipleDevices' { "List of devices attached`nprivate-device device`nsecond-device device" }
                default { "List of devices attached`nprivate-device device" }
            }
            return @{ ExitCode = 0; Output = $devices }
        }
        if ($command -match 'am start ') {
            $state.Opens++
            if ($state.Scenario -eq 'Reappeared' -and $state.Opens -eq 2) { $state.Enabled = $true }
        }
        if ($command -match 'input tap 50 150$') {
            $state.Enabled = $true; $state.EnableTaps++
            if ($state.Scenario -eq 'EnableTapTimeout') {
                $state.Now += $TimeoutMilliseconds
                throw 'The tap reached the device but its reply was lost.'
            }
        }
        if ($command -match 'input tap 50 250$') {
            $state.StopTaps++; $state.StopReported = $true
            if ($state.Scenario -ne 'FalseStopSuccess') { $state.Enabled = $false }
        }
        if ($command -match 'input tap 50 550$') {
            $state.DiagnosticTaps++
            $state.DiagnosticsExpanded = -not $state.DiagnosticsExpanded
        }
        if ($command -match 'input swipe (\d+) (\d+) (\d+) (\d+) (\d+)$') {
            if (-not $state.Scrolling -or $state.Scenario -eq 'ForeignScroll') { throw 'No App-owned viewport permits a swipe.' }
            $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
            if ($x1 -le 20 -or $x1 -ge 300 -or $x2 -ne $x1 -or $y1 -le 80 -or $y1 -ge 900 -or $y2 -le 80 -or $y2 -ge 900 -or $y1 -eq $y2 -or $Matches[5] -ne '250') {
                throw 'Swipe escaped the current App viewport.'
            }
            $state.Swipes++
            $state.Now += 250
            $state.Page = [Math]::Clamp($state.Page + $(if ($y1 -gt $y2) { 1 } else { -1 }), 0, 2)
        }
        if ($command -match 'pidof ') {
            $stellar = $command -match ':redirector$'
            if ($state.Scenario -eq 'Preexisting' -and $stellar) { return @{ ExitCode = 0; Output = '123' } }
            if ($state.Enabled) {
                $both = $state.Scenario -in @('BothAlive', 'MissingStopButton', 'SpoofedStopButton', 'MissingBinderProof')
                $isShizuku = $state.Choice -eq 'Shizuku' -and $state.Scenario -ne 'WrongBackend'
                if ($both -or ($stellar -and -not $isShizuku) -or (-not $stellar -and $isShizuku)) {
                    return @{ ExitCode = 0; Output = '123' }
                }
            }
            return @{ ExitCode = 1; Output = '' }
        }
        if ($command -match 'exec-out cat ') {
            if ($state.Scenario -eq 'MalformedXml' -and $state.EnableTaps -gt 0 -and $state.Opens -lt 2) {
                return @{ ExitCode = 0; Output = '<private-device https://private.invalid/?key=private-key>' }
            }
            $status = if ($state.Enabled -and -not $state.StopReported) {
                if ($state.Scenario -eq 'AmbiguousSource') { 'Binder 来源无法唯一确认' }
                elseif ($state.Scenario -eq 'WrongVersion') { '服务版本：v0.0.0 控制器：运行中' }
                else { '控制器：运行中' }
            } elseif ($state.Scenario -eq 'PermissionDenied' -and $state.EnableTaps -eq 0) {
                '授权：尚未允许'
            } elseif ($state.Scenario -eq 'BackendDown' -and $state.EnableTaps -eq 0) {
                '权限后端：未运行'
            } else {
                if ($state.StopReported) { '停止完成：服务已退出，不会自动重连' } else { '状态：已停止' }
            }
            $diagnostics = ''
            if ($state.DiagnosticsExpanded) {
                $details = if ($state.StopReported) {
                    if ($state.Scenario -eq 'MissingBinderProof') { "停止完成 [$($fixtureMetadata.BuildLabel)]" }
                    else { "停止完成 [$($fixtureMetadata.BuildLabel)] 已确认 UserService Binder 死亡" }
                } elseif ($state.Scenario -eq 'WrongVersion') { '服务版本：v0.0.0' }
                else { "服务版本：$($fixtureMetadata.BuildLabel)（协议 $($fixtureMetadata.ProtocolVersion)） 服务代：$($fixtureMetadata.ServiceGeneration)" }
                $diagnostics = "<node package='$($state.AppPackage)' resource-id='$($state.AppPackage):id/diagnostics_text' text='$details' />"
            }
            $diagnosticLabel = if ($state.DiagnosticsExpanded) { '收起诊断信息 ▾' } else { '诊断信息 ▸' }
            $buttonPackage = if ($state.Scenario -eq 'SpoofedStopButton') { 'unrelated.app' } else { $state.AppPackage }
            $stopLabel = if ($state.Scenario -eq 'MissingStopButton') { 'unavailable' } else { '停用并退出服务' }
            $choiceLabel = switch ($state.Choice) { 'Shizuku' { 'Shizuku 服务' }; 'Stellar' { 'Stellar（原生 API）' }; default { '自动（推荐）' } }
            $xmlText = @"
<hierarchy><node package="$($state.AppPackage)">
<node package="$($state.AppPackage)" text="开启接管" clickable="true" enabled="true" bounds="[0,100][100,200]" />
<node package="$buttonPackage" text="$stopLabel" clickable="true" enabled="true" bounds="[0,200][100,300]" />
<node package="$($state.AppPackage)" text="刷新状态" clickable="true" enabled="true" bounds="[0,300][100,400]" />
<node package="$($state.AppPackage)" text="$choiceLabel" resource-id="$($state.AppPackage):id/backend_spinner" clickable="true" bounds="[0,400][100,500]" />
<node package="$($state.AppPackage)" resource-id="$($state.AppPackage):id/status_text" text="$status" />
<node package="$($state.AppPackage)" text="$diagnosticLabel" resource-id="$($state.AppPackage):id/diagnostics_toggle" clickable="true" enabled="true" bounds="[0,500][100,600]" />
$diagnostics
</node></hierarchy>
"@
            if ($state.Scrolling) {
                [xml]$window = $xmlText
                $page = $window.hierarchy.node
                $page.SetAttribute('resource-id', "$($state.AppPackage):id/page_scroll")
                $page.SetAttribute('scrollable', 'true')
                $page.SetAttribute('bounds', '[20,80][300,900]')
                if ($state.Scenario -eq 'ForeignScroll') { $page.SetAttribute('package', 'unrelated.app') }
                foreach ($node in @($page.SelectNodes('./node'))) {
                    $id = [string]$node.'resource-id'
                    $visiblePage = if ($id -like '*:id/diagnostics_text') { 2 } elseif ($id -like '*:id/diagnostics_toggle' -or $id -like '*:id/status_text') { 1 } else { 0 }
                    $unreachable = ($state.Scenario -in @('DiagnosticsUnreachable', 'ForeignScroll') -and $id -like '*:id/diagnostics_toggle') -or
                        ($state.Scenario -eq 'StopUnreachable' -and $state.EnableTaps -gt 0 -and [string]$node.text -eq '停用并退出服务')
                    if ($state.Page -ne $visiblePage -or $unreachable) { [void]$page.RemoveChild($node) }
                }
                $xmlText = $window.OuterXml
            }
            return @{ ExitCode = 0; Output = $xmlText }
        }
        if ($command -match 'install ') { return @{ ExitCode = 0; Output = 'Success' } }
        if ($command -notmatch 'uiautomator dump |input tap |input swipe |shell rm -f |am start ') { throw 'Unexpected fixture command.' }
        return @{ ExitCode = 0; Output = '' }
    }.GetNewClosure()
    return @{ State = $state; Transport = $transport }
}

function Invoke-Fixture($Fixture) {
    # Execute the actual workflow, replacing only external tool/time boundaries.
    $tokens = $null; $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw 'Production script parse failed.' }
    $functions = $ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] }
    $tailOffset = $functions[-1].Extent.EndOffset
    $workflow = ($functions | ForEach-Object {
        if ($_.Name -eq 'Invoke-AdbProcess') {
            'function Invoke-AdbProcess { param([string[]]$Arguments, [int]$TimeoutMilliseconds) & $transport -Arguments $Arguments -TimeoutMilliseconds $TimeoutMilliseconds }'
        } elseif ($_.Name -eq 'Get-MonotonicMilliseconds') {
            'function Get-MonotonicMilliseconds { return $fixtureState.Now }'
        } elseif ($_.Name -eq 'Assert-VerificationApkSignature') {
            'function Assert-VerificationApkSignature { param($Apk, $SdkRoot) Assert-SignedApk -Apk $Apk -SdkRoot $SdkRoot }'
        } else { $_.Extent.Text }
    }) -join "`n"
    $workflow += "`n" + $source.Substring($tailOffset)
    $transport = $Fixture.Transport
    $fixtureState = $Fixture.State
    $adb = $ScriptPath # Only Test-Path observes this; never executed.
    $deviceArgs = @(); $SkipInstall = $fixtureState.Scenario -notin @('InvalidSignature', 'ValidSignature')
    $Backend = $fixtureState.Choice
    $Serial = if ($fixtureState.Scenario -eq 'WrongSerial') { 'unknown-private-device' } else { $null }
    $packageName = $metadata.PackageName
    $stellarProcess = "$packageName`:redirector"
    $shizukuProcess = "$packageName`:redirector_shizuku"
    $remoteXml = '/sdcard/codex-redirector-00000000000000000000000000000000.xml'
    function Start-Sleep { param($Milliseconds, $Seconds) $fixtureState.Now += $Milliseconds + 1000 * $Seconds }
    function Assert-SignedApk {
        param($Apk, $SdkRoot)
        $fixtureState.Signatures++
        if ($fixtureState.Scenario -eq 'InvalidSignature') { throw 'Signature verification failed.' }
    }
    & ([scriptblock]::Create($workflow))
}

$passed = 0
$cases = @(
    @{ Name = 'BothAlive'; Recovery = 'CONFIRMED'; Stops = $true; Reason = 'mutual exclusion failed' },
    @{ Name = 'EnableTapTimeout'; Recovery = 'CONFIRMED'; Stops = $true },
    @{ Name = 'WrongVersion'; Recovery = 'CONFIRMED'; Stops = $true },
    @{ Name = 'MalformedXml'; Recovery = 'CONFIRMED'; Stops = $true },
    @{ Name = 'Reappeared'; Recovery = 'CONFIRMED'; Stops = $true },
    @{ Name = 'Disconnected'; Recovery = 'UNCONFIRMED'; Stops = $false },
    @{ Name = 'HungAdb'; Recovery = 'UNCONFIRMED'; Stops = $false },
    @{ Name = 'FalseStopSuccess'; Recovery = 'UNCONFIRMED'; Stops = $true },
    @{ Name = 'MissingBinderProof'; Recovery = 'UNCONFIRMED'; Stops = $true },
    @{ Name = 'MissingStopButton'; Recovery = 'UNCONFIRMED'; Stops = $false },
    @{ Name = 'SpoofedStopButton'; Recovery = 'UNCONFIRMED'; Stops = $false },
    @{ Name = 'Preexisting'; Recovery = 'NOT_REQUIRED'; Stops = $false },
    @{ Name = 'PermissionDenied'; Recovery = 'NOT_REQUIRED'; Stops = $false },
    @{ Name = 'BackendDown'; Recovery = 'NOT_REQUIRED'; Stops = $false },
    @{ Name = 'NoDevice'; Recovery = 'NOT_REQUIRED'; Stops = $false },
    @{ Name = 'MultipleDevices'; Recovery = 'NOT_REQUIRED'; Stops = $false },
    @{ Name = 'WrongSerial'; Recovery = 'NOT_REQUIRED'; Stops = $false },
    @{ Name = 'InvalidSignature'; Recovery = 'NOT_REQUIRED'; Stops = $false },
    @{ Name = 'AmbiguousSource'; Choice = 'Shizuku'; Recovery = 'CONFIRMED'; Stops = $true; Reason = 'source is ambiguous' },
    @{ Name = 'WrongBackend'; Choice = 'Shizuku'; Recovery = 'CONFIRMED'; Stops = $true; Reason = 'Shizuku UserService is missing' },
    @{ Name = 'Success'; Choice = 'Auto' },
    @{ Name = 'Success'; Choice = 'Shizuku' },
    @{ Name = 'Success'; Choice = 'Stellar' },
    @{ Name = 'ValidSignature'; Choice = 'Auto' },
    @{ Name = 'Offscreen'; Choice = 'Auto' },
    @{ Name = 'DiagnosticsUnreachable'; Recovery = 'UNCONFIRMED'; Stops = $true },
    @{ Name = 'StopUnreachable'; Recovery = 'UNCONFIRMED'; Stops = $false },
    @{ Name = 'ForeignScroll'; Recovery = 'UNCONFIRMED'; Stops = $true }
)
foreach ($case in $cases) {
    $choice = if ($case.Choice) { $case.Choice } else { 'Auto' }
    $fixture = New-Fixture -Scenario $case.Name -Choice $choice
    $failure = $null; $output = @()
    try { $output = @(Invoke-Fixture $fixture 6>&1) } catch { $failure = $_.Exception.Message }
    $label = "$($case.Name)/$choice"
    if ($case.Recovery) {
        Assert-True ($null -ne $failure) "$label must report verification failure."
        if ($case.Reason) { Assert-True ($failure -like "*$($case.Reason)*") "$label did not reach the intended assertion." }
        Assert-True ($failure -match "VERIFICATION FAILED:.*RECOVERY: $($case.Recovery)") "${label}: wrong verification/recovery result."
        Assert-True (($fixture.State.StopTaps -gt 0) -eq $case.Stops) "${label}: unexpected App stop attempts."
        if ($case.Recovery -eq 'CONFIRMED') { Assert-True (-not $fixture.State.Enabled) "${label}: service still enabled after claimed recovery." }
    } else {
        Assert-True ($null -eq $failure) "$label unexpectedly failed: $failure"
        Assert-True (($output -join ' ') -match 'PASS:.*returned to page and stayed stopped') "$label missing page-return result."
        Assert-True (($output -join ' ') -match 'NOT TESTED: Activity recreation; real app-process cold start') "$label must disclaim recreation and cold start."
        Assert-True (-not $fixture.State.Enabled -and $fixture.State.StopTaps -eq 1) "$label must finish stopped."
        Assert-True ($fixture.State.DiagnosticsExpanded -and $fixture.State.DiagnosticTaps -eq 1) "$label must open diagnostics once and retain Binder proof."
    }
    if ($case.Name -in @('InvalidSignature', 'ValidSignature')) {
        Assert-True ($fixture.State.Signatures -eq 1) "$label must validate the signature before installation."
        if ($case.Name -eq 'InvalidSignature') { Assert-True ($fixture.State.Commands.Count -eq 0) "$label must not reach ADB." }
    }
    if ($fixture.State.Scrolling) {
        Assert-True ($fixture.State.Swipes -le 80) "$label exceeded its bounded scroll budget."
        if ($case.Name -eq 'ForeignScroll') { Assert-True ($fixture.State.Swipes -eq 0) "$label scrolled another app's viewport." }
        else { Assert-True ($fixture.State.Swipes -gt 0) "$label never exercised scrolling." }
    }
    $reported = "$failure $($output -join ' ')"
    Assert-True ($reported -notmatch 'private-device|private\.invalid|private-key') "$label leaked sensitive fixture content."
    Assert-True (($fixture.State.Commands -join "`n") -notmatch 'force-stop|kill-server|\bkill\b|\bpkill\b|\bstopservice\b') "$label attempted a forbidden device/manager mutation."
    Assert-True (($fixture.State.Timeouts | Where-Object { $_ -le 0 -or $_ -gt 60000 }).Count -eq 0) "$label has an unbounded ADB call."
    Assert-True ($fixture.State.Now -lt 215000) "$label exceeded the verification + recovery budgets."
    Write-Host "PASS: $label"
    $passed++
}
Write-Host "GREEN: $passed offline workflow cases; no real ADB or device used."
