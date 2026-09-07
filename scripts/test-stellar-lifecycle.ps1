$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dependencyRoot = Join-Path $projectRoot '.gradle-user-home/caches/modules-2/files-2.1'
$junit = Get-ChildItem (Join-Path $dependencyRoot 'junit/junit/4.13.2') -Recurse -Filter 'junit-4.13.2.jar' | Select-Object -First 1
$hamcrest = Get-ChildItem (Join-Path $dependencyRoot 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter 'hamcrest-core-1.3.jar' | Select-Object -First 1
if (-not $junit -or -not $hamcrest) { throw 'Project-local JUnit cache is required.' }
$output = Join-Path $projectRoot 'app/build/stellar-lifecycle-harness'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$classpath = $junit.FullName + [IO.Path]::PathSeparator + $hamcrest.FullName
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'stellar-harness') -Filter *.java -Recurse | ForEach-Object FullName)
$sources += @('NativeUserServiceArgs', 'NativeStellarUserService') | ForEach-Object { Join-Path $projectRoot "app/src/main/java/dev/codex/mibrowserredirector/stellar/$_.java" }
$sources += Join-Path $projectRoot 'app/src/main/java/dev/codex/mibrowserredirector/UserServiceStopper.java'
& javac --release 17 -encoding UTF-8 -cp $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Stellar lifecycle harness compilation failed.' }
& java '-Dfile.encoding=UTF-8' -cp ($output + [IO.Path]::PathSeparator + $classpath) org.junit.runner.JUnitCore dev.codex.mibrowserredirector.stellar.NativeStellarLifecycleTest dev.codex.mibrowserredirector.StellarSupervisedCleanupTest
if ($LASTEXITCODE -ne 0) { throw 'Stellar lifecycle regression is RED. This is a simulated transport, not phone evidence.' }
