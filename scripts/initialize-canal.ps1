param([Parameter(Mandatory=$true)][string]$DistributionPath,[switch]$EnableTestDestination)
$ErrorActionPreference='Stop'
$repairBackendRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repairDistribution=(Resolve-Path -LiteralPath $DistributionPath).Path
if(!(Test-Path -LiteralPath (Join-Path $repairDistribution 'lib/canal.deployer-1.1.8.jar'))){throw 'Use the official canal.deployer 1.1.8 distribution'}
$repairRuntime=Join-Path $repairBackendRoot 'target/canal-server'
$repairPrivate=Get-Content -LiteralPath (Join-Path $repairBackendRoot 'config/local-canal.json') -Raw|ConvertFrom-Json
if($repairPrivate.REPAIR_CDC_DB_USER -notmatch '^repair_cdc_[a-f0-9]{8}$' -or $repairPrivate.REPAIR_CDC_DB_PASSWORD -notmatch '^[a-f0-9]{24,30}$'){throw 'Configure the dedicated replication account in config/local-canal.json; use a 24-30 character random password'}
$repairState=Join-Path $repairBackendRoot 'config/local-canal-process.json'
if(Test-Path -LiteralPath $repairState){
    $repairRecorded=Get-Content -LiteralPath $repairState -Raw|ConvertFrom-Json
    if($repairRecorded.canal -gt 0 -and (Get-Process -Id $repairRecorded.canal -ErrorAction SilentlyContinue)){throw 'Stop this project Canal process before initializing its files'}
}
New-Item -ItemType Directory -Path $repairRuntime -Force|Out-Null
Copy-Item -LiteralPath (Join-Path $repairDistribution 'lib') -Destination $repairRuntime -Recurse -Force
$repairConf=Join-Path $repairRuntime 'conf'
New-Item -ItemType Directory -Path $repairConf -Force|Out-Null
# Only distribution libraries/Spring templates; never import another destination or credentials.
Copy-Item -LiteralPath (Join-Path $repairDistribution 'conf/spring') -Destination $repairConf -Recurse -Force
function Write-RepairProperties([string]$Path,[System.Collections.IDictionary]$Values){
    $repairLines=foreach($repairEntry in $Values.GetEnumerator()){
        $repairEscaped=[Text.StringBuilder]::new()
        foreach($repairCharacter in ([string]$repairEntry.Value).ToCharArray()){
            if($repairCharacter -eq '\'){[void]$repairEscaped.Append('\\')}
            elseif([int]$repairCharacter -gt 127){[void]$repairEscaped.Append(('\u{0:x4}' -f [int]$repairCharacter))}
            else{[void]$repairEscaped.Append($repairCharacter)}
        }
        $repairEntry.Key+'='+$repairEscaped.ToString()
    }
    [IO.File]::WriteAllLines($Path,$repairLines,[Text.Encoding]::ASCII)
}
$repairAdminSecret=-join ((1..24)|ForEach-Object { '{0:x2}' -f [Security.Cryptography.RandomNumberGenerator]::GetInt32(256) })
$repairValues=[ordered]@{
    'canal.ip'='127.0.0.1';'canal.port'='11112';'canal.metrics.pull.port'='0';'canal.admin.port'='11115';'canal.admin.user'='repair-admin';'canal.admin.passwd'=$repairAdminSecret
    'canal.serverMode'='tcp';'canal.destinations'=$(if($EnableTestDestination){'repair,repair-test'}else{'repair'})
    'canal.conf.dir'=$repairConf.Replace('\','/');'canal.file.data.dir'=$repairConf.Replace('\','/')
    'canal.auto.scan'='false';'canal.auto.reset.latest.pos.mode'='false';'canal.instance.global.mode'='spring';'canal.instance.global.lazy'='false'
    'canal.instance.global.spring.xml'='classpath:spring/file-instance.xml';'canal.instance.tsdb.enable'='false'
    'canal.instance.binlog.format'='ROW';'canal.instance.binlog.image'='FULL';'canal.instance.filter.field'='';'canal.instance.filter.black.field'=''
    'canal.instance.detecting.sql'='SELECT 1';'canal.instance.parser.parallel'='false';'canal.instance.parser.parallelThreadSize'='2'
    'canal.mq.topic'='unused';'canal.mq.partition'='0';'canal.mq.partitionsNum'='1';'canal.mq.partitionHash'='';'canal.mq.dynamicTopic'='';'canal.mq.dynamicTopicPartitionNum'='1';'canal.mq.enableDynamicQueuePartition'='false'
    'canal.instance.master.journal.name'='';'canal.instance.master.position'='';'canal.instance.master.timestamp'='';'canal.instance.master.gtid'=''
    'canal.instance.standby.address'='';'canal.instance.standby.journal.name'='';'canal.instance.standby.position'='';'canal.instance.standby.timestamp'='';'canal.instance.standby.gtid'=''
}
Write-RepairProperties (Join-Path $repairConf 'canal.properties') $repairValues
$repairDestinations=@(@{name='repair';pattern='repair_system\.repair_.*';slave='521121'})
if($EnableTestDestination){$repairDestinations+=@{name='repair-test';pattern='repair_test_.*\.repair_.*';slave='521122'}}
foreach($repairDestination in $repairDestinations){
    $repairFolder=Join-Path $repairConf $repairDestination.name
    New-Item -ItemType Directory -Path $repairFolder -Force|Out-Null
    Write-RepairProperties (Join-Path $repairFolder 'instance.properties') ([ordered]@{
        'canal.instance.mysql.slaveId'=$repairDestination.slave;'canal.instance.master.address'='127.0.0.1:3306'
        'canal.instance.dbUsername'=$repairPrivate.REPAIR_CDC_DB_USER;'canal.instance.dbPassword'=$repairPrivate.REPAIR_CDC_DB_PASSWORD
        'canal.instance.connectionCharset'='UTF-8';'canal.instance.filter.regex'=$repairDestination.pattern;'canal.instance.tsdb.enable'='false'
    })
}
$repairLogging='<configuration><appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender"><file>logs/canal.log</file><rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy"><fileNamePattern>logs/canal.%d{yyyy-MM-dd}.log</fileNamePattern><maxHistory>7</maxHistory></rollingPolicy><encoder><pattern>%date %-5level %logger{30} - %msg%n</pattern></encoder></appender><logger name="com.alibaba.otter.canal.parse.inbound.mysql.MysqlConnection" level="WARN"/><root level="INFO"><appender-ref ref="FILE"/></root></configuration>'
[IO.File]::WriteAllText((Join-Path $repairConf 'logback.xml'),$repairLogging)
Write-Output 'RepairSystem Canal initialized. Existing cursor files were preserved.'
