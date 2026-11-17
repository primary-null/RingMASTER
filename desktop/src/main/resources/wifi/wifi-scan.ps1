# RingMASTER Wi-Fi scan (read-only): the Wi-Fi adapters, the current link of each, and the networks in range.
# Never connects, disconnects or changes a network, and never reads a saved profile or password.
# One compact JSON object per line on stdout (UTF-8, no BOM):
#   adapter, link, ap, cycle, note (NO_ADAPTER, WLAN_SERVICE, LOCATION_OFF), error, done
# Windows 11 24H2 and later refuse scans, network names and the connection details to desktop apps while Location is off.
# That is reported as a LOCATION_OFF note. The adapter list, the radio state, the channel and the signal still come out.
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$OutputEncoding = [Console]::OutputEncoding
$Seconds = __SECONDS__
$ErrorActionPreference = 'Stop'
$clock = [Diagnostics.Stopwatch]::StartNew()
function Emit($o) { [Console]::Out.WriteLine(($o | ConvertTo-Json -Depth 4 -Compress)); [Console]::Out.Flush() }
function Ms { [int64]$clock.ElapsedMilliseconds }
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class RingWlanIface {
    public string Id; public string Description; public int State;
    public int SwRadio; public int HwRadio;
    public int Channel = -1; public int Rssi = int.MinValue; public int ConnError = -1;
    public string Ssid; public string Bssid;
    public int Phy = -1; public int Quality = -1; public long RxKbps = -1; public long TxKbps = -1; public int Auth = -1; public int Cipher = -1;
}
public class RingWlanResult { public int Error; public RingWlanIface[] Items = new RingWlanIface[0]; }
public static class RingWlan {
    [DllImport("wlanapi.dll")] static extern int WlanOpenHandle(uint v, IntPtr r, out uint negotiated, out IntPtr h);
    [DllImport("wlanapi.dll")] static extern int WlanCloseHandle(IntPtr h, IntPtr r);
    [DllImport("wlanapi.dll")] static extern int WlanEnumInterfaces(IntPtr h, IntPtr r, out IntPtr list);
    [DllImport("wlanapi.dll")] static extern int WlanQueryInterface(IntPtr h, ref Guid g, int op, IntPtr r, out int size, out IntPtr data, out int type);
    [DllImport("wlanapi.dll")] static extern void WlanFreeMemory(IntPtr p);
    static int Q(IntPtr h, Guid g, int op, out IntPtr data) { int size, type; return WlanQueryInterface(h, ref g, op, IntPtr.Zero, out size, out data, out type); }
    static void Fill(IntPtr h, Guid g, RingWlanIface f) {
        IntPtr d;
        // Radio state per PHY: 3 words each after a count (index, software state, hardware state). 1 is on, 2 is off.
        if (Q(h, g, 4, out d) == 0 && d != IntPtr.Zero) {
            int n = Math.Min(Marshal.ReadInt32(d, 0), 64);
            bool swOn = false, swOff = false, hwOn = false, hwOff = false;
            for (int i = 0; i < n; i++) {
                int sw = Marshal.ReadInt32(d, 8 + 12 * i), hw = Marshal.ReadInt32(d, 12 + 12 * i);
                if (sw == 1) swOn = true; if (sw == 2) swOff = true; if (hw == 1) hwOn = true; if (hw == 2) hwOff = true;
            }
            f.SwRadio = swOn ? 1 : (swOff ? 2 : 0); f.HwRadio = hwOn ? 1 : (hwOff ? 2 : 0);
            WlanFreeMemory(d);
        }
        if (Q(h, g, 8, out d) == 0 && d != IntPtr.Zero) { f.Channel = Marshal.ReadInt32(d, 0); WlanFreeMemory(d); }
        if (Q(h, g, 0x10000102, out d) == 0 && d != IntPtr.Zero) { f.Rssi = Marshal.ReadInt32(d, 0); WlanFreeMemory(d); }
        f.ConnError = Q(h, g, 7, out d);
        if (f.ConnError == 0 && d != IntPtr.Zero) {
            // WLAN_CONNECTION_ATTRIBUTES: state 0, mode 4, profile name 8 (not read), association 520, security 588.
            int len = Math.Min(Math.Max(Marshal.ReadInt32(d, 520), 0), 32);
            byte[] ssid = new byte[len]; Marshal.Copy(IntPtr.Add(d, 524), ssid, 0, len);
            f.Ssid = System.Text.Encoding.UTF8.GetString(ssid);
            byte[] mac = new byte[6]; Marshal.Copy(IntPtr.Add(d, 560), mac, 0, 6);
            f.Bssid = BitConverter.ToString(mac).Replace('-', ':');
            f.Phy = Marshal.ReadInt32(d, 568); f.Quality = Marshal.ReadInt32(d, 576);
            f.RxKbps = (uint)Marshal.ReadInt32(d, 580); f.TxKbps = (uint)Marshal.ReadInt32(d, 584);
            f.Auth = Marshal.ReadInt32(d, 596); f.Cipher = Marshal.ReadInt32(d, 600);
            WlanFreeMemory(d);
        }
    }
    public static RingWlanResult Snapshot() {
        var result = new RingWlanResult(); uint neg; IntPtr h;
        result.Error = WlanOpenHandle(2, IntPtr.Zero, out neg, out h);
        if (result.Error != 0) return result;
        try {
            IntPtr list; result.Error = WlanEnumInterfaces(h, IntPtr.Zero, out list);
            if (result.Error != 0) return result;
            try {
                int count = Marshal.ReadInt32(list, 0);
                var items = new RingWlanIface[count];
                for (int i = 0; i < count; i++) {
                    IntPtr item = IntPtr.Add(list, 8 + i * 532);
                    byte[] gb = new byte[16]; Marshal.Copy(item, gb, 0, 16);
                    Guid g = new Guid(gb);
                    var f = new RingWlanIface();
                    f.Id = g.ToString("B").ToUpperInvariant();
                    f.Description = Marshal.PtrToStringUni(IntPtr.Add(item, 16), 256).Split('\0')[0];
                    f.State = Marshal.ReadInt32(item, 528);
                    Fill(h, g, f);
                    items[i] = f;
                }
                result.Items = items;
            } finally { WlanFreeMemory(list); }
        } finally { WlanCloseHandle(h, IntPtr.Zero); }
        return result;
    }
}
'@
$asTaskOp = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
$asTaskAction = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' } | Select-Object -First 1
function Await($op, [Type]$type) { $t = $asTaskOp.MakeGenericMethod($type).Invoke($null, @($op)); [void]$t.Wait(-1); $t.Result }
function AwaitAction($op) { $t = $asTaskAction.Invoke($null, @($op)); [void]$t.Wait(-1) }
$stateNames = @{ 0 = 'not_ready'; 1 = 'connected'; 2 = 'ad_hoc'; 3 = 'disconnecting'; 4 = 'disconnected'; 5 = 'associating'; 6 = 'discovering'; 7 = 'authenticating' }
$radioNames = @{ 0 = 'unknown'; 1 = 'on'; 2 = 'off' }
# DOT11_AUTH_ALGORITHM and DOT11_CIPHER_ALGORITHM as the names the WinRT enumerations use.
$authNames = @{ 1 = 'Open80211'; 2 = 'SharedKey80211'; 3 = 'Wpa'; 4 = 'WpaPsk'; 5 = 'WpaNone'; 6 = 'Rsna'; 7 = 'RsnaPsk'; 8 = 'Wpa3'; 9 = 'Wpa3Sae'; 10 = 'Owe'; 11 = 'Wpa3Enterprise' }
$cipherNames = @{ 0 = 'None'; 1 = 'Wep40'; 2 = 'Tkip'; 4 = 'Ccmp'; 5 = 'Wep104'; 8 = 'Gcmp'; 9 = 'Gcmp256'; 257 = 'Wep' }

# 1. The adapters: the interfaces Windows lists for physical IEEE 802.11 hardware, joined to the Native Wifi view by GUID.
$native = [RingWlan]::Snapshot()
if ($native.Error -eq 1062 -or $native.Error -eq 1060) { Emit ([ordered]@{ t = 'note'; note = 'WLAN_SERVICE' }); Emit ([ordered]@{ t = 'done'; ms = (Ms) }); exit 0 }
$airplane = $false
try { $airplane = ((Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\RadioManagement' -Name SystemRadioState -ErrorAction Stop).SystemRadioState -eq 1) } catch { }
$cim = @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceType -eq 71 -or $_.NdisPhysicalMedium -eq 9 -or $_.NdisPhysicalMedium -eq 1 })
$adapters = @()
foreach ($a in $cim) {
    $guid = "$($a.InterfaceGuid)".ToUpperInvariant()
    $n = $native.Items | Where-Object { $_.Id -eq $guid } | Select-Object -First 1
    $pci = $null
    try { $h = Get-NetAdapterHardwareInfo -Name $a.Name -ErrorAction Stop; if ($null -ne $h.BusNumber) { $pci = '{0:x4}:{1:x2}:{2:x2}.{3:x}' -f [int]$h.SegmentNumber, [int]$h.BusNumber, [int]$h.DeviceNumber, [int]$h.FunctionNumber } } catch { }
    $mac = "$($a.MacAddress)".Replace('-', '')
    $rx = $null; $tx = $null
    if ($a.ReceiveLinkSpeed -gt 0) { $rx = [math]::Round($a.ReceiveLinkSpeed / 1e6, 1) }
    if ($a.TransmitLinkSpeed -gt 0) { $tx = [math]::Round($a.TransmitLinkSpeed / 1e6, 1) }
    Emit ([ordered]@{ t = 'adapter'; id = $guid; name = $a.InterfaceDescription; address = $mac; status = "$($a.Status)"; state = $(if ($n) { $stateNames[[int]$n.State] } else { $null })
        radio = $(if ($n) { $radioNames[[int]$n.SwRadio] } else { 'unknown' }); hwRadio = $(if ($n) { $radioNames[[int]$n.HwRadio] } else { 'unknown' }); airplane = $airplane
        rxMbps = $rx; txMbps = $tx; driver = "$($a.DriverVersionString)"; pnp = "$($a.PnPDeviceID)"; pci = $pci })
    $adapters += $guid
}
if ($adapters.Count -eq 0) { Emit ([ordered]@{ t = 'note'; note = 'NO_ADAPTER' }); Emit ([ordered]@{ t = 'done'; ms = (Ms) }); exit 0 }

function Emit-Links($snapshot) {
    foreach ($f in $snapshot.Items) {
        if ($adapters -notcontains $f.Id) { continue }
        $connected = ($f.State -eq 1)
        $o = [ordered]@{ t = 'link'; ms = (Ms); adapter = $f.Id; connected = $connected }
        if ($connected) {
            if ($f.Channel -gt 0) { $o['channel'] = $f.Channel }
            if ($f.Rssi -ne [int]::MinValue -and $f.Rssi -lt 0) { $o['rssi'] = $f.Rssi }
            if ($f.ConnError -eq 0) {
                $o['ssid'] = $f.Ssid; $o['bssid'] = $f.Bssid; $o['phy'] = $f.Phy; $o['quality'] = $f.Quality
                $o['rxMbps'] = [math]::Round($f.RxKbps / 1000.0, 1); $o['txMbps'] = [math]::Round($f.TxKbps / 1000.0, 1)
                $o['auth'] = $authNames[[int]$f.Auth]; $o['cipher'] = $cipherNames[[int]$f.Cipher]
            }
        }
        Emit $o
    }
}
Emit-Links $native

# 2. Windows decides whether this app may see networks at all.
$WF = [Windows.Devices.WiFi.WiFiAdapter, Windows.Devices.WiFi, ContentType = WindowsRuntime]
$access = 'Unspecified'
try { $access = "$(Await ($WF::RequestAccessAsync()) ([Windows.Devices.WiFi.WiFiAccessStatus, Windows.Devices.WiFi, ContentType = WindowsRuntime]))" } catch { Emit ([ordered]@{ t = 'error'; stage = 'access'; message = $_.Exception.Message }) }
if ($access -ne 'Allowed') {
    Emit ([ordered]@{ t = 'note'; note = 'LOCATION_OFF'; access = $access })
    Emit ([ordered]@{ t = 'cycle'; ms = (Ms) })
    Emit ([ordered]@{ t = 'done'; ms = (Ms) })
    exit 0
}

# 3. Scan as often as the window allows. Adapters whose radio is off are not asked.
$found = @()
try { $found = @(Await ($WF::FindAllAdaptersAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.WiFi.WiFiAdapter]])) } catch { Emit ([ordered]@{ t = 'error'; stage = 'adapters'; message = $_.Exception.Message }) }
$cycle = 0
do {
    $cycle++
    $passStart = Ms
    $snapshot = [RingWlan]::Snapshot()
    foreach ($w in $found) {
        $id = "$($w.NetworkAdapter.NetworkAdapterId.ToString('B'))".ToUpperInvariant()
        $f = $snapshot.Items | Where-Object { $_.Id -eq $id } | Select-Object -First 1
        if ($f -and $f.SwRadio -eq 2) { continue }
        try {
            AwaitAction ($w.ScanAsync())
            foreach ($n in $w.NetworkReport.AvailableNetworks) {
                if ($n.IsWiFiDirect) { continue }
                $s = $n.SecuritySettings
                Emit ([ordered]@{ t = 'ap'; ms = (Ms); adapter = $id; ssid = $n.Ssid; bssid = $n.Bssid; rssi = $n.NetworkRssiInDecibelMilliwatts
                    khz = $n.ChannelCenterFrequencyInKilohertz; phy = [int]$n.PhyKind; auth = "$($s.NetworkAuthenticationType)"; enc = "$($s.NetworkEncryptionType)" })
            }
        } catch { Emit ([ordered]@{ t = 'error'; stage = 'scan'; message = $_.Exception.Message }) }
    }
    Emit-Links $snapshot
    Emit ([ordered]@{ t = 'cycle'; ms = (Ms) })
    # Scans closer than three seconds apart are refused or ignored by the adapter.
    $spent = (Ms) - $passStart
    if ($spent -lt 3000) { Start-Sleep -Milliseconds (3000 - $spent) }
} while ((Ms) + 4000 -lt ($Seconds * 1000))
Emit ([ordered]@{ t = 'done'; ms = (Ms) })
