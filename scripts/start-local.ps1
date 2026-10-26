param([switch]$RestartBackend)
$ErrorActionPreference='Stop'
$repairBackendRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repairStatePath=Join-Path $repairBackendRoot 'config/local-processes.json'
$repairProcesses=@{backend=0;ocr=0}
if(Test-Path -LiteralPath $repairStatePath){
    $repairPrevious=Get-Content -LiteralPath $repairStatePath -Raw|ConvertFrom-Json
    foreach($repairProperty in $repairPrevious.PSObject.Properties){if($repairProcesses.ContainsKey($repairProperty.Name)){$repairProcesses[$repairProperty.Name]=[int]$repairProperty.Value}}
}
foreach($repairRelative in @('config/local-runtime.json','config/local-integrations.json')){
    $repairPath=Join-Path $repairBackendRoot $repairRelative
    if(Test-Path -LiteralPath $repairPath){
        $repairValues=Get-Content -LiteralPath $repairPath -Raw|ConvertFrom-Json
        foreach($repairProperty in $repairValues.PSObject.Properties){
            $repairValue=if($repairProperty.Value -is [bool]){$repairProperty.Value.ToString().ToLowerInvariant()}else{[string]$repairProperty.Value}
            [Environment]::SetEnvironmentVariable($repairProperty.Name,$repairValue,'Process')
        }
    }
}
function Test-RepairHealth([string]$Url){
    try{$repairHealth=Invoke-RestMethod $Url -TimeoutSec 2;return $repairHealth.status -eq 'UP'}catch{return $false}
}
function Wait-RepairHealth([string]$Url){
    $repairDeadline=[DateTime]::UtcNow.AddSeconds(45)
    while([DateTime]::UtcNow -lt $repairDeadline){if(Test-RepairHealth $Url){return};Start-Sleep -Seconds 1}
    throw "Service did not become ready: $Url. Check target/local-*.log"
}
$repairTarget=Join-Path $repairBackendRoot 'target'
New-Item -ItemType Directory -Path $repairTarget -Force|Out-Null
try{
    if($env:REPAIR_CANAL_ENABLED -eq 'true'){& (Join-Path $PSScriptRoot 'start-canal.ps1')}
    if($env:REPAIR_OCR_ENABLED -eq 'true' -and $env:REPAIR_OCR_PROVIDER -eq 'LOCAL'){
        if(!(Test-RepairHealth 'http://127.0.0.1:8601/health')){
            if(Get-NetTCPConnection -State Listen -LocalPort 8601 -ErrorAction SilentlyContinue){throw 'Port 8601 is occupied by an unready service'}
            $repairOcrRoot=Join-Path $repairBackendRoot 'services/ocr'
            $repairPython=Join-Path $repairOcrRoot '.venv/Scripts/python.exe'
            if(!(Test-Path -LiteralPath $repairPython)){throw 'Install services/ocr/requirements.lock in its .venv first'}
            $repairModel=Start-Process -FilePath $repairPython -ArgumentList @('-m','uvicorn','app:app','--host','127.0.0.1','--port','8601','--no-access-log') -WorkingDirectory $repairOcrRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $repairTarget 'local-ocr.log') -RedirectStandardError (Join-Path $repairTarget 'local-ocr-error.log') -PassThru
            $repairProcesses.ocr=$repairModel.Id
            Wait-RepairHealth 'http://127.0.0.1:8601/health'
        }
    }
    $repairPort=if($env:REPAIR_PORT){[int]$env:REPAIR_PORT}else{8080}
    $repairListeners=Get-NetTCPConnection -State Listen -LocalPort $repairPort -ErrorAction SilentlyContinue
    if($repairListeners){
        $repairProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($repairProcesses.backend)"
        if(!$repairProcess -or $repairProcess.CommandLine -notlike ('*'+(Join-Path $repairBackendRoot 'target/repair-system.jar')+'*') -or $repairListeners.OwningProcess -notcontains $repairProcesses.backend){throw "Port $repairPort is owned by another application; it was not stopped"}
        if($RestartBackend){Stop-Process -Id $repairProcesses.backend;Wait-Process -Id $repairProcesses.backend -Timeout 15 -ErrorAction SilentlyContinue;$repairListeners=$null}
    }
    if(!$repairListeners){
        $repairJava=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java.exe -ErrorAction Stop).Source}
        $repairJar=Join-Path $repairTarget 'repair-system.jar'
        if(!(Test-Path -LiteralPath $repairJar)){throw 'Run mvn -DskipTests package first'}
        $repairBackend=Start-Process -FilePath $repairJava -ArgumentList @('-jar',('"'+$repairJar+'"'),'--server.address=127.0.0.1') -WorkingDirectory $repairBackendRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $repairTarget 'local-startup.log') -RedirectStandardError (Join-Path $repairTarget 'local-startup-error.log') -PassThru
        $repairProcesses.backend=$repairBackend.Id
    }
    Wait-RepairHealth "http://127.0.0.1:$repairPort/actuator/health"
    Write-Output "RepairSystem backend ready: http://127.0.0.1:$repairPort/actuator/health"
}finally{
    $repairProcesses|ConvertTo-Json|Set-Content -LiteralPath $repairStatePath -Encoding UTF8
}
