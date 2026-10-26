$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repairBackendRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
foreach($repairRelative in @('config/local-runtime.json','config/local-integrations.json')){
    $repairValues=Get-Content -LiteralPath (Join-Path $repairBackendRoot $repairRelative) -Raw|ConvertFrom-Json
    foreach($repairProperty in $repairValues.PSObject.Properties){[Environment]::SetEnvironmentVariable($repairProperty.Name,[string]$repairProperty.Value,'Process')}
}
$repairLibs=Join-Path $repairBackendRoot 'target/cache-check-libs'
New-Item -ItemType Directory -Path $repairLibs -Force|Out-Null
$repairArchive=[IO.Compression.ZipFile]::OpenRead((Join-Path $repairBackendRoot 'target/repair-system.jar'))
try{foreach($repairEntry in $repairArchive.Entries){if($repairEntry.FullName.StartsWith('BOOT-INF/lib/') -and $repairEntry.FullName.EndsWith('.jar')){[IO.Compression.ZipFileExtensions]::ExtractToFile($repairEntry,(Join-Path $repairLibs ([IO.Path]::GetFileName($repairEntry.FullName))),$true)}}}finally{$repairArchive.Dispose()}
$repairJava=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java.exe -ErrorAction Stop).Source}
Push-Location $repairBackendRoot
try{& $repairJava --class-path ('target/classes;'+$repairLibs+'/*') (Join-Path $PSScriptRoot 'RepairReadCacheCheck.java');if($LASTEXITCODE -ne 0){throw 'Cache/Canal is not fresh; check target/read-cache-status.json'}}finally{Pop-Location}
