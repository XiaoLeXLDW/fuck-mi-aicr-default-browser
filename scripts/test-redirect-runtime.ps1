param([switch]$CompileService)
# Scoped JUnit loop: no Gradle, Android device, network, or shared output directory.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dependencyRoot = Join-Path $projectRoot '.gradle-user-home/caches/modules-2/files-2.1'
$junit = Get-ChildItem (Join-Path $dependencyRoot 'junit/junit/4.13.2') -Recurse -Filter 'junit-4.13.2.jar' | Select-Object -First 1
$hamcrest = Get-ChildItem (Join-Path $dependencyRoot 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter 'hamcrest-core-1.3.jar' | Select-Object -First 1
if (-not $junit -or -not $hamcrest) { throw 'Project-local JUnit dependencies are missing.' }
$output = Join-Path $projectRoot 'app/build/redirect-runtime-tests/current'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = $junit.FullName + [IO.Path]::PathSeparator + $hamcrest.FullName
$sources = @('RedirectDispatcher', 'AmProcessLauncher') | ForEach-Object { Join-Path $projectRoot "app/src/main/java/dev/codex/mibrowserredirector/$_.java" }
$sources += @('RedirectDispatcherTest', 'AmProcessLauncherTest') | ForEach-Object { Join-Path $projectRoot "app/src/test/java/dev/codex/mibrowserredirector/$_.java" }
$tests = @('dev.codex.mibrowserredirector.RedirectDispatcherTest', 'dev.codex.mibrowserredirector.AmProcessLauncherTest')
& javac --release 17 -encoding UTF-8 -cp $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Redirect runtime test compilation failed.' }
& java -cp ($output + [IO.Path]::PathSeparator + $classpath) org.junit.runner.JUnitCore @tests
if ($LASTEXITCODE -ne 0) { throw 'Redirect runtime regression failed.' }
if ($CompileService) {
    # Compile only our production integration against existing generated AIDL.
    # Never invoke Gradle or regenerate/write its shared outputs.
    $androidJar = Join-Path $projectRoot '.tools/android-sdk/platforms/android-35/android.jar'
    $serviceSources = @('RedirectDispatcher', 'AmProcessLauncher', 'RedirectorUserService', 'SystemActivityController', 'ServiceIdentity', 'UrlExtractor') |
        ForEach-Object { Join-Path $projectRoot "app/src/main/java/dev/codex/mibrowserredirector/$_.java" }
    $serviceSources += Join-Path $projectRoot 'app/build/generated/aidl_source_output_dir/release/out/dev/codex/mibrowserredirector/IRedirectorService.java'
    $serviceSources += Join-Path $projectRoot 'hidden-api-stub/build/generated/aidl_source_output_dir/release/out/android/app/IActivityController.java'
    $serviceOutput = Join-Path $output 'service-compile'
    New-Item -ItemType Directory -Force -Path $serviceOutput | Out-Null
    & javac --release 17 -encoding UTF-8 -cp $androidJar -d $serviceOutput @serviceSources
    if ($LASTEXITCODE -ne 0) { throw 'Scoped Android service compilation failed.' }
    Write-Host 'Scoped Android service compilation passed (not a full APK build).'
}
