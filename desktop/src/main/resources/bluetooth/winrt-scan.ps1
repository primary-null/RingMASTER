# RingMASTER Bluetooth discovery (read-only): WinRT DeviceWatcher over Bluetooth Classic + LE association endpoints.
# Never pairs, connects or changes the radio. Streams one compact JSON object per line on stdout as devices are heard:
#   {"t":"adapter",...}  {"t":"note","note":"NO_ADAPTER"}  {"t":"add","ms":..,"id":..,...}  {"t":"upd","ms":..,"id":..,...}  {"t":"done","ms":..}
$Seconds = __SECONDS__
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -TypeDefinition @'
using System.Collections.Concurrent;
public class RingBtEvent { public long Ms; public bool Added; public object Info; }
public class RingBtSink {
    public readonly System.Diagnostics.Stopwatch Clock = System.Diagnostics.Stopwatch.StartNew();
    public readonly ConcurrentQueue<RingBtEvent> Events = new ConcurrentQueue<RingBtEvent>();
    public void OnAdded(object sender, object info) { Events.Enqueue(new RingBtEvent { Ms = Clock.ElapsedMilliseconds, Added = true, Info = info }); }
    public void OnUpdated(object sender, object update) { Events.Enqueue(new RingBtEvent { Ms = Clock.ElapsedMilliseconds, Added = false, Info = update }); }
}
'@
$asTaskOp = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
function Await($op, [Type]$type) { $t = $asTaskOp.MakeGenericMethod($type).Invoke($null, @($op)); [void]$t.Wait(-1); $t.Result }
$DI = [Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime]
$DW = [Windows.Devices.Enumeration.DeviceWatcher, Windows.Devices.Enumeration, ContentType = WindowsRuntime]
$BA = [Windows.Devices.Bluetooth.BluetoothAdapter, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
$RA = [Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime]

function Emit($o) { [Console]::Out.WriteLine(($o | ConvertTo-Json -Depth 4 -Compress)); [Console]::Out.Flush() }
$adapter = Await ($BA::GetDefaultAsync()) $BA
if ($null -eq $adapter) { Emit ([ordered]@{ t = 'note'; note = 'NO_ADAPTER' }); exit 0 }
$radio = Await ($adapter.GetRadioAsync()) $RA
$info = Await ($DI::CreateFromIdAsync($adapter.DeviceId)) $DI
$on = ($null -ne $radio) -and ("$($radio.State)" -eq 'On')
Emit ([ordered]@{ t = 'adapter'; id = $adapter.DeviceId; name = $info.Name; classic = $adapter.IsClassicSupported; le = $adapter.IsLowEnergySupported; address = ('{0:X12}' -f $adapter.BluetoothAddress); powered = $on })
if (-not $on) { Emit ([ordered]@{ t = 'note'; note = 'ADAPTER_OFF' }); exit 0 }

$aqs = 'System.Devices.Aep.ProtocolId:="{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}" OR System.Devices.Aep.ProtocolId:="{bb7bb05e-5972-42b5-94fc-76eaa7084d49}"'
$props = [string[]]@('System.Devices.Aep.DeviceAddress', 'System.Devices.Aep.IsConnected', 'System.Devices.Aep.IsPaired', 'System.Devices.Aep.IsPresent',
  'System.Devices.Aep.SignalStrength', 'System.Devices.Aep.ProtocolId', 'System.Devices.Aep.Bluetooth.Cod.Major', 'System.Devices.Aep.Bluetooth.Cod.Minor',
  'System.Devices.Aep.Bluetooth.Le.Appearance.Category')
$watcher = $DI::CreateWatcher($aqs, $props, [Windows.Devices.Enumeration.DeviceInformationKind]::AssociationEndpoint)
$sink = New-Object RingBtSink
foreach ($pair in @(@('Added', 'OnAdded'), @('Updated', 'OnUpdated'))) {
  $ev = $DW.GetEvent($pair[0])
  $handler = [Delegate]::CreateDelegate($ev.EventHandlerType, $sink, [RingBtSink].GetMethod($pair[1]))
  if ($pair[0] -eq 'Added') { [void]$watcher.add_Added($handler) } else { [void]$watcher.add_Updated($handler) }
}

function Read-Props($map) { $h = @{}; foreach ($kv in $map) { $h[$kv.Key.Substring($kv.Key.LastIndexOf('.') + 1)] = $kv.Value }; $h }
function Drain {
  $e = $null
  while ($sink.Events.TryDequeue([ref]$e)) {
    $p = Read-Props $e.Info.Properties
    if ($e.Added) {
      Emit ([ordered]@{ t = 'add'; ms = $e.Ms; id = $e.Info.Id; name = $e.Info.Name; address = $p['DeviceAddress']; paired = $p['IsPaired']; connected = $p['IsConnected']
        present = $p['IsPresent']; rssi = $p['SignalStrength']; protocol = "$($p['ProtocolId'])"; major = $p['Major']; minor = $p['Minor']; leCategory = $p['Category'] })
    } else {
      $o = [ordered]@{ t = 'upd'; ms = $e.Ms; id = $e.Info.Id }
      foreach ($k in @('SignalStrength', 'IsConnected', 'IsPaired', 'IsPresent')) { if ($p.ContainsKey($k)) { $o[$k] = $p[$k] } }
      Emit $o
    }
  }
}
$watcher.Start()
while ($sink.Clock.ElapsedMilliseconds -lt ($Seconds * 1000)) { Drain; Start-Sleep -Milliseconds 80 }
try { $watcher.Stop() } catch { }
Drain
Emit ([ordered]@{ t = 'done'; ms = $sink.Clock.ElapsedMilliseconds })
