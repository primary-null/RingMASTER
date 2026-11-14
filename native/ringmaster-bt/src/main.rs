//! ringmaster-bt: RingMASTER's Windows Bluetooth scanner.
//!
//! Read-only discovery through the WinRT DeviceWatcher over Bluetooth Classic and LE association endpoints.
//! It never pairs, connects or changes the radio. Output is one compact JSON object per line on stdout, the same
//! protocol as the PowerShell fallback (`bluetooth/winrt-scan.ps1`), so the app parses both with one parser:
//!   {"t":"adapter",...}  {"t":"note","note":"NO_ADAPTER"}  {"t":"add","ms":..,"id":..,...}
//!   {"t":"upd","ms":..,"id":..,...}  {"t":"done","ms":..}
//!
//! Usage: ringmaster-bt --seconds N   (N is clamped to 3..30)

use serde_json::{Map, Value, json};
use std::io::{Read, Write};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use windows::Devices::Bluetooth::BluetoothAdapter;
use windows::Devices::Enumeration::{DeviceInformation, DeviceInformationKind, DeviceInformationUpdate, DeviceWatcher};
use windows::Devices::Radios::RadioState;
use windows::Foundation::Collections::{IIterable, IMapView};
use windows::Foundation::{IPropertyValue, PropertyType, TypedEventHandler};
use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize};
use windows::core::{HSTRING, IInspectable, Interface, Result};

const AQS: &str = "System.Devices.Aep.ProtocolId:=\"{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}\" OR System.Devices.Aep.ProtocolId:=\"{bb7bb05e-5972-42b5-94fc-76eaa7084d49}\"";
const PROPS: [&str; 9] = [
    "System.Devices.Aep.DeviceAddress",
    "System.Devices.Aep.IsConnected",
    "System.Devices.Aep.IsPaired",
    "System.Devices.Aep.IsPresent",
    "System.Devices.Aep.SignalStrength",
    "System.Devices.Aep.ProtocolId",
    "System.Devices.Aep.Bluetooth.Cod.Major",
    "System.Devices.Aep.Bluetooth.Cod.Minor",
    "System.Devices.Aep.Bluetooth.Le.Appearance.Category",
];

static OUT: Mutex<()> = Mutex::new(());

fn emit(value: &Value) {
    let _guard = OUT.lock().unwrap_or_else(|e| e.into_inner());
    let mut out = std::io::stdout().lock();
    // A closed pipe means the app stopped listening; exit quietly.
    if writeln!(out, "{value}").and_then(|_| out.flush()).is_err() {
        std::process::exit(0);
    }
}

/// First line. The app ignores `t=hello` and uses it to tell this build from an older helper.
fn hello_line() -> Value {
    json!({ "t": "hello", "v": env!("CARGO_PKG_VERSION") })
}

/// Exit when the app closes our stdin. A normal scan keeps the pipe open until it stops us.
fn stop_when_stdin_closes() {
    std::thread::spawn(|| {
        let mut input = std::io::stdin();
        let mut buf = [0u8; 8];
        loop {
            match input.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(_) => break,
            }
        }
        // No "done" line: the app reports that the scan ended early.
        std::process::exit(0);
    });
}

/// The listening window from `--seconds N`: 10 when absent or unreadable, always within 3..30.
fn parse_seconds(args: &[String]) -> u64 {
    let n = args.iter().position(|a| a == "--seconds").and_then(|i| args.get(i + 1)).and_then(|v| v.parse::<u64>().ok()).unwrap_or(10);
    n.clamp(3, 30)
}

fn seconds_from_args() -> u64 {
    parse_seconds(&std::env::args().collect::<Vec<_>>())
}

/// A WinRT property value as JSON. Unknown types become null rather than failing the scan.
fn to_json(value: &IInspectable) -> Value {
    let Ok(pv) = value.cast::<IPropertyValue>() else { return Value::Null };
    let Ok(kind) = pv.Type() else { return Value::Null };
    let v = match kind {
        PropertyType::Boolean => pv.GetBoolean().map(Value::from),
        PropertyType::Int16 => pv.GetInt16().map(Value::from),
        PropertyType::UInt16 => pv.GetUInt16().map(Value::from),
        PropertyType::Int32 => pv.GetInt32().map(Value::from),
        PropertyType::UInt32 => pv.GetUInt32().map(Value::from),
        PropertyType::Int64 => pv.GetInt64().map(Value::from),
        PropertyType::UInt8 => pv.GetUInt8().map(Value::from),
        PropertyType::String => pv.GetString().map(|s| Value::from(s.to_string())),
        PropertyType::Guid => pv.GetGuid().map(|g| Value::from(format!("{g:?}").to_lowercase())),
        _ => return Value::Null,
    };
    v.unwrap_or(Value::Null)
}

/// Short property name ("SignalStrength") to value, for the keys present in [map].
fn read_props(map: &IMapView<HSTRING, IInspectable>) -> Map<String, Value> {
    let mut out = Map::new();
    for key in PROPS {
        let k = HSTRING::from(key);
        if let Ok(true) = map.HasKey(&k) {
            let value = map.Lookup(&k).map(|v| to_json(&v)).unwrap_or(Value::Null);
            out.insert(key.rsplit('.').next().unwrap_or(key).to_string(), value);
        }
    }
    out
}

fn run() -> Result<()> {
    emit(&hello_line());
    let seconds = seconds_from_args();
    let adapter = match BluetoothAdapter::GetDefaultAsync()?.get() {
        Ok(a) => a,
        Err(_) => {
            emit(&json!({ "t": "note", "note": "NO_ADAPTER" }));
            return Ok(());
        }
    };
    let radio = adapter.GetRadioAsync()?.get().ok();
    let info = DeviceInformation::CreateFromIdAsync(&adapter.DeviceId()?)?.get()?;
    let on = radio.as_ref().and_then(|r| r.State().ok()) == Some(RadioState::On);
    emit(&json!({
        "t": "adapter",
        "id": adapter.DeviceId()?.to_string(),
        "name": info.Name()?.to_string(),
        "classic": adapter.IsClassicSupported()?,
        "le": adapter.IsLowEnergySupported()?,
        "address": format!("{:012X}", adapter.BluetoothAddress()?),
        "powered": on,
    }));
    if !on {
        emit(&json!({ "t": "note", "note": "ADAPTER_OFF" }));
        return Ok(());
    }

    let props = IIterable::<HSTRING>::try_from(PROPS.iter().map(|p| HSTRING::from(*p)).collect::<Vec<_>>())?;
    let watcher = DeviceInformation::CreateWatcherWithKindAqsFilterAndAdditionalProperties(
        &HSTRING::from(AQS), &props, DeviceInformationKind::AssociationEndpoint)?;
    let start = Instant::now();

    watcher.Added(&TypedEventHandler::<DeviceWatcher, DeviceInformation>::new(move |_, info| {
        let ms = start.elapsed().as_millis() as u64;
        if let Some(info) = info.as_ref() {
            let p = info.Properties().map(|m| read_props(&m)).unwrap_or_default();
            let get = |k: &str| p.get(k).cloned().unwrap_or(Value::Null);
            emit(&json!({
                "t": "add", "ms": ms,
                "id": info.Id().map(|s| s.to_string()).unwrap_or_default(),
                "name": info.Name().map(|s| s.to_string()).unwrap_or_default(),
                "address": get("DeviceAddress"), "paired": get("IsPaired"), "connected": get("IsConnected"),
                "present": get("IsPresent"), "rssi": get("SignalStrength"), "protocol": get("ProtocolId"),
                "major": get("Major"), "minor": get("Minor"), "leCategory": get("Category"),
            }));
        }
        Ok(())
    }))?;
    watcher.Updated(&TypedEventHandler::<DeviceWatcher, DeviceInformationUpdate>::new(move |_, update| {
        let ms = start.elapsed().as_millis() as u64;
        if let Some(update) = update.as_ref() {
            let p = update.Properties().map(|m| read_props(&m)).unwrap_or_default();
            let mut o = Map::new();
            o.insert("t".into(), "upd".into());
            o.insert("ms".into(), ms.into());
            o.insert("id".into(), update.Id().map(|s| s.to_string()).unwrap_or_default().into());
            for k in ["SignalStrength", "IsConnected", "IsPaired", "IsPresent"] {
                if let Some(v) = p.get(k) { o.insert(k.into(), v.clone()); }
            }
            emit(&Value::Object(o));
        }
        Ok(())
    }))?;

    watcher.Start()?;
    std::thread::sleep(Duration::from_secs(seconds));
    let _ = watcher.Stop();
    // Give the watcher a moment to deliver events already queued before the stop.
    std::thread::sleep(Duration::from_millis(150));
    emit(&json!({ "t": "done", "ms": start.elapsed().as_millis() as u64 }));
    Ok(())
}

fn main() {
    stop_when_stdin_closes();
    // SAFETY: called once on the main thread before any WinRT use; the multithreaded apartment lets watcher
    // callbacks arrive on pool threads while the main thread sleeps.
    unsafe { let _ = RoInitialize(RO_INIT_MULTITHREADED); }
    if let Err(e) = run() {
        eprintln!("ringmaster-bt: {}", e.message());
        std::process::exit(1);
    }
    std::process::exit(0);
}

#[cfg(test)]
mod tests {
    use super::{hello_line, parse_seconds};
    fn args(v: &[&str]) -> Vec<String> { v.iter().map(|s| s.to_string()).collect() }

    #[test]
    fn seconds_are_clamped_and_defaulted() {
        assert_eq!(parse_seconds(&args(&["bt", "--seconds", "12"])), 12);
        assert_eq!(parse_seconds(&args(&["bt", "--seconds", "500"])), 30);
        assert_eq!(parse_seconds(&args(&["bt", "--seconds", "1"])), 3);
        assert_eq!(parse_seconds(&args(&["bt", "--seconds", "x"])), 10);
        assert_eq!(parse_seconds(&args(&["bt"])), 10);
    }

    #[test]
    fn hello_names_a_version() {
        let v = hello_line();
        assert_eq!(v["t"], "hello");
        assert!(v["v"].as_str().unwrap().len() > 0);
    }
}
