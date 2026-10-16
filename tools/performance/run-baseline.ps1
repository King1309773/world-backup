param([string]$MainClasses = '')
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location -LiteralPath $repository
try {
    $jdk = Join-Path $repository '.toolchains\jdk8\jdk8u504-b01'
    if (-not $MainClasses) { $MainClasses = Join-Path $repository 'build\classes\java\main' }
    $mapped = Get-ChildItem (Join-Path $repository 'build\fg_cache\net\minecraftforge\forge') -Recurse -File -Filter '*mapped*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $mapped -or -not (Test-Path -LiteralPath $MainClasses)) {
        throw 'Compile the project before running the snapshot baseline.'
    }
    $output = Join-Path $repository 'build\performance'
    $classes = Join-Path $output 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $log4j = Get-ChildItem (Join-Path $gradleCache 'caches\modules-2\files-2.1\org.apache.logging.log4j') -Recurse -File -Filter '*.jar' |
        Where-Object { $_.Name -like 'log4j-api-*' -or $_.Name -like 'log4j-core-*' } |
        Select-Object -ExpandProperty FullName
    $classpath = $MainClasses + ';' + (Join-Path $repository 'build\classes\java\main') + ';' + $mapped + ';' + ($log4j -join ';')
    & (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $classes (Join-Path $PSScriptRoot 'SnapshotBaseline.java')
    if ($LASTEXITCODE -ne 0) { throw 'Diagnostic compilation failed.' }
    & (Join-Path $jdk 'bin\java.exe') -Xmx512m -cp ($classes + ';' + $classpath) com.rlcmj.worldbackup.runtime.SnapshotBaseline $output
    if ($LASTEXITCODE -ne 0) { throw 'Snapshot baseline failed.' }
} finally {
    Pop-Location
}
