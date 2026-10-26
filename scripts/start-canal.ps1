$ErrorActionPreference='Stop'
$repairBackendRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repairRuntime=Join-Path $repairBackendRoot 'target/canal-server'
$repairState=Join-Path $repairBackendRoot 'config/local-canal-process.json'
$repairListeners=Get-NetTCPConnection -State Listen -LocalPort 11112 -ErrorAction SilentlyContinue
if($repairListeners){
    if(!(Test-Path -LiteralPath $repairState)){throw 'Port 11112 is occupied; no process was stopped'}
    $repairRecorded=Get-Content -LiteralPath $repairState -Raw|ConvertFrom-Json
    $repairProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($repairRecorded.canal)"
    if(!$repairProcess -or $repairProcess.CommandLine -notlike ('*'+$repairRuntime+'*') -or $repairProcess.CommandLine -notmatch 'CanalLauncher' -or $repairListeners.OwningProcess -notcontains $repairRecorded.canal){throw 'Port 11112 belongs to another service'}
    Write-Output 'RepairSystem Canal already listening on 127.0.0.1:11112';return
}
if(!(Test-Path -LiteralPath (Join-Path $repairRuntime 'conf/canal.properties'))){throw 'Run scripts/initialize-canal.ps1 -DistributionPath <canal.deployer-1.1.8> first'}
$repairJava=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java.exe -ErrorAction Stop).Source}
$repairCanal=Start-Process -FilePath $repairJava -ArgumentList @('-Xms128m','-Xmx384m',('-Dcanal.conf="'+$repairRuntime+'/conf/canal.properties"'),('-Dlogback.configurationFile="'+$repairRuntime+'/conf/logback.xml"'),'-classpath',('"'+$repairRuntime+'/conf;'+$repairRuntime+'/lib/*"'),'com.alibaba.otter.canal.deployer.CanalLauncher') -WorkingDirectory $repairRuntime -WindowStyle Hidden -RedirectStandardOutput (Join-Path $repairRuntime 'console.log') -RedirectStandardError (Join-Path $repairRuntime 'console-error.log') -PassThru
@{canal=$repairCanal.Id}|ConvertTo-Json|Set-Content -LiteralPath $repairState -Encoding UTF8
$repairDeadline=[DateTime]::UtcNow.AddSeconds(25)
while([DateTime]::UtcNow -lt $repairDeadline){
    if($repairCanal.HasExited){throw 'Canal exited; check target/canal-server/logs/canal.log'}
    if(Get-NetTCPConnection -State Listen -LocalPort 11112 -ErrorAction SilentlyContinue){Write-Output 'RepairSystem Canal listening on 127.0.0.1:11112';return}
    Start-Sleep -Milliseconds 250
}
throw 'Canal did not listen within 25 seconds; check its logs'
