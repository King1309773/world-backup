$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location -LiteralPath $repository
try {
    $jdk = Join-Path $repository '.toolchains\jdk8\jdk8u504-b01'
    $mainClasses = Join-Path $repository 'build\classes\java\main'
    $mapped = Get-ChildItem (Join-Path $repository 'build\fg_cache\net\minecraftforge\forge') -Recurse -File -Filter '*mapped*.jar' |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $mapped -or -not (Test-Path -LiteralPath $mainClasses)) {
        throw 'Compile the project before running the snapshot baseline.'
    }
    $output = Join-Path $repository 'build\performance'
    $classes = Join-Path $output 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $classpath = $mainClasses + ';' + $mapped
    & (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $classes (Join-Path $PSScriptRoot 'SnapshotBaseline.java')
    if ($LASTEXITCODE -ne 0) { throw 'Diagnostic compilation failed.' }
    & (Join-Path $jdk 'bin\java.exe') -Xmx512m -cp ($classes + ';' + $classpath) com.rlcmj.worldbackup.runtime.SnapshotBaseline $output
    if ($LASTEXITCODE -ne 0) { throw 'Snapshot baseline failed.' }
} finally {
    Pop-Location
}
