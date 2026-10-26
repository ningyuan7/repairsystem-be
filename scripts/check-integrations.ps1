param([switch]$CheckOcr)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repairBackendRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
foreach($repairRelative in @('config/local-runtime.json','config/local-integrations.json')) {
    $repairConfig=Join-Path $repairBackendRoot $repairRelative
    if(Test-Path -LiteralPath $repairConfig) {
        $repairValues=Get-Content -LiteralPath $repairConfig -Raw | ConvertFrom-Json
        foreach($repairProperty in $repairValues.PSObject.Properties) {
            $repairValue=if($repairProperty.Value -is [bool]){$repairProperty.Value.ToString().ToLowerInvariant()}else{[string]$repairProperty.Value}
            [Environment]::SetEnvironmentVariable($repairProperty.Name,$repairValue,'Process')
        }
    }
}
$repairJar=Join-Path $repairBackendRoot 'target/repair-system.jar'
$repairClasses=Join-Path $repairBackendRoot 'target/classes'
if(!(Test-Path -LiteralPath $repairJar) -or !(Test-Path -LiteralPath $repairClasses)) { throw 'Run mvn -DskipTests package first' }
$repairLibrariesRoot=Join-Path $repairBackendRoot 'target/integration-check-libs'
New-Item -ItemType Directory -Path $repairLibrariesRoot -Force | Out-Null
$repairLibraries=[Collections.Generic.List[string]]::new()
$repairArchive=[IO.Compression.ZipFile]::OpenRead($repairJar)
try {
    foreach($repairEntry in $repairArchive.Entries) {
        if(!$repairEntry.FullName.StartsWith('BOOT-INF/lib/') -or !$repairEntry.FullName.EndsWith('.jar')) { continue }
        $repairName=[IO.Path]::GetFileName($repairEntry.FullName)
        if($repairName -notmatch '^[A-Za-z0-9_.-]+\.jar$') { throw 'Invalid library filename' }
        $repairTarget=Join-Path $repairLibrariesRoot $repairName
        [IO.Compression.ZipFileExtensions]::ExtractToFile($repairEntry,$repairTarget,$true)
        $repairLibraries.Add($repairTarget)
    }
} finally {$repairArchive.Dispose()}
$repairClassPath=[string]::Join([IO.Path]::PathSeparator,@($repairClasses)+$repairLibraries.ToArray())
$repairJava=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{'java'}
$repairArguments=@('--class-path',$repairClassPath,(Join-Path $PSScriptRoot 'RepairIntegrationCheck.java'))
if($CheckOcr){$repairArguments+='--check-ocr'}
Push-Location -LiteralPath $repairBackendRoot
try {
    & $repairJava @repairArguments
    if($LASTEXITCODE -ne 0){throw 'Integration check failed; see target/integration-check.json (no credentials included)'}
} finally {Pop-Location}
