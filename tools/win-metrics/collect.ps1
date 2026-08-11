$ErrorActionPreference='SilentlyContinue'
$os=Get-CimInstance Win32_OperatingSystem
$c=Get-Counter '\Processor(_Total)\% Processor Time'
Start-Sleep -Milliseconds 400
$c2=Get-Counter '\Processor(_Total)\% Processor Time'
echo ('cpu '+[math]::Round(($c.CounterSamples[0].CookedValue+$c2.CounterSamples[0].CookedValue)/2,1))
echo '@MEM'
$tm=[long]$os.TotalVisibleMemorySize*1024
$fm=[long]$os.FreePhysicalMemory*1024
echo ('Mem: '+$tm+' '+($tm-$fm))
echo '@DISK'
Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object { $t=[long]$_.Size; $f=[long]$_.FreeSpace; if($t -gt 0){ $u=$t-$f; echo ($_.DeviceID+' '+[long]($t/1024)+' '+[long]($u/1024)+' '+[long]($f/1024)+' '+[int](100*$u/$t)+'% '+$_.DeviceID) } }
echo '@NET'
Get-CimInstance Win32_PerfRawData_Tcpip_NetworkInterface | Where-Object { $_.Name -ne 'Loopback' } | ForEach-Object { echo ($_.Name+': '+$_.BytesReceivedPersec+' 0 0 0 0 0 0 0 '+$_.BytesSentPersec+' 0 0 0 0 0 0 0') }
echo '@PROC'
Get-Process | Sort-Object CPU -Descending | Select-Object -First 8 | ForEach-Object { $cv=0.0; if($_.CPU){$cv=[math]::Round($_.CPU,1)}; $ws=[long]$_.WorkingSet64; $pm=0.0; if($tm -gt 0){$pm=[math]::Round(100.0*$ws/$tm,1)}; echo ($_.Id.ToString()+' '+$cv+' '+$pm+' '+[long]($ws/1024)+' '+$_.ProcessName) }
echo '@UPTIME'
echo ([int64]((Get-Date)-$os.LastBootUpTime).TotalSeconds)
echo '@LOAD'
echo '0 0 0'
echo '@OS'
echo ('PRETTY_NAME='+$os.Caption)
echo '@KERNEL'
echo ($os.Version+' build '+$os.BuildNumber)
echo '@CPU'
echo $env:NUMBER_OF_PROCESSORS
