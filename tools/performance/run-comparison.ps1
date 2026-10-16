param(
    [Parameter(Mandatory=$true)][string]$BaselineClasses,
    [string]$CandidateClasses = ''
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location -LiteralPath $repository
try {
    if (-not $CandidateClasses) { $CandidateClasses = Join-Path $repository 'build\classes\java\main' }
    $BaselineClasses = (Resolve-Path -LiteralPath $BaselineClasses).Path
    $CandidateClasses = (Resolve-Path -LiteralPath $CandidateClasses).Path
    $jdk = Join-Path $repository '.toolchains\jdk8\jdk8u504-b01'
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $log4j = Get-ChildItem (Join-Path $gradleCache 'caches\modules-2\files-2.1\org.apache.logging.log4j') -Recurse -File -Filter '*.jar' |
        Where-Object { $_.Name -like 'log4j-api-*' -or $_.Name -like 'log4j-core-*' } |
        Select-Object -ExpandProperty FullName
    $mapped = Get-ChildItem 'build\fg_cache\net\minecraftforge\forge' -Recurse -File -Filter '*mapped*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    $classes = Join-Path $repository 'build\performance\comparison-classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -d $classes (Join-Path $PSScriptRoot 'SnapshotComparison.java')
    if ($LASTEXITCODE -ne 0) { throw 'Comparison compilation failed.' }
    $cp = $classes + ';' + $CandidateClasses + ';' + (Join-Path $repository 'build\classes\java\main') + ';' + $mapped + ';' + ($log4j -join ';')
    & (Join-Path $jdk 'bin\java.exe') -Xmx512m ('-Dlog4j.configurationFile=' + (Join-Path $PSScriptRoot 'log4j2.xml')) -cp $cp com.rlcmj.worldbackup.benchmark.SnapshotComparison (Join-Path $repository 'build\performance') $BaselineClasses $CandidateClasses
    if ($LASTEXITCODE -ne 0) { throw 'Snapshot comparison failed.' }
} finally {
    Pop-Location
}
