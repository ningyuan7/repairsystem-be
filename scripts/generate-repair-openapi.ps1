$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repairProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repairArtifact = Join-Path $repairProjectRoot 'target/repair-system.jar'
$repairClasses = Join-Path $repairProjectRoot 'target/classes'
if (!(Test-Path -LiteralPath $repairArtifact) -or !(Test-Path -LiteralPath $repairClasses)) {
    throw '请先运行 mvn -DskipTests package'
}
$repairLibraryRoot = Join-Path $repairProjectRoot 'target/repair-openapi-libs'
New-Item -ItemType Directory -Path $repairLibraryRoot -Force | Out-Null
$repairLibraries = [Collections.Generic.List[string]]::new()
$repairArchive = [IO.Compression.ZipFile]::OpenRead($repairArtifact)
try {
    foreach ($repairEntry in $repairArchive.Entries) {
        if (!$repairEntry.FullName.StartsWith('BOOT-INF/lib/') -or !$repairEntry.FullName.EndsWith('.jar')) { continue }
        $repairName = [IO.Path]::GetFileName($repairEntry.FullName)
        if ($repairName -notmatch '^[A-Za-z0-9_.-]+\.jar$') { throw '非法依赖文件名' }
        $repairTarget = Join-Path $repairLibraryRoot $repairName
        [IO.Compression.ZipFileExtensions]::ExtractToFile($repairEntry, $repairTarget, $true)
        $repairLibraries.Add($repairTarget)
    }
} finally { $repairArchive.Dispose() }
$repairClassPath = [string]::Join([IO.Path]::PathSeparator, @($repairClasses) + $repairLibraries.ToArray())
$repairJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
Push-Location -LiteralPath $repairProjectRoot
try {
    & $repairJava --class-path $repairClassPath (Join-Path $PSScriptRoot 'RepairOpenApi.java')
    if ($LASTEXITCODE -ne 0) { throw 'OpenAPI 生成失败' }
} finally { Pop-Location }
