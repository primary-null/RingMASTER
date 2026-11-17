//! "Who am I": what this Engine is, where it runs and what it can see. Read from files only; no command is run.

use crate::json::{object, string};
use std::path::Path;

fn read(path: &str) -> Option<String> {
    let text = std::fs::read_to_string(path).ok()?;
    let t = text.trim();
    (!t.is_empty() && t.len() <= 256).then(|| t.to_string())
}

fn os_release() -> (String, String, String) {
    let text = std::fs::read_to_string("/etc/os-release").or_else(|_| std::fs::read_to_string("/usr/lib/os-release")).unwrap_or_default();
    let mut id = String::new();
    let mut version = String::new();
    let mut pretty = String::new();
    for line in text.lines().take(64) {
        let Some((k, v)) = line.split_once('=') else { continue };
        let v: String = v.trim().trim_matches('"').chars().take(80).collect();
        match k {
            "ID" => id = v,
            "VERSION_ID" => version = v,
            "PRETTY_NAME" => pretty = v,
            _ => {}
        }
    }
    (id, version, pretty)
}

/// `node` (a Proxmox VE host), `container`, `vm`, `machine` (bare metal) or `unknown`, with the detail it came from.
pub fn kind() -> (&'static str, String) {
    if Path::new("/etc/pve").is_dir() && Path::new("/usr/bin/pveversion").exists() {
        return ("node", "proxmox-ve".into());
    }
    if let Some(c) = read("/run/systemd/container") {
        return ("container", c);
    }
    if std::fs::read_to_string("/proc/self/mountinfo").is_ok_and(|m| m.contains("lxcfs")) {
        return ("container", "lxc".into());
    }
    if Path::new("/.dockerenv").exists() {
        return ("container", "docker".into());
    }
    let vendor = read("/sys/class/dmi/id/sys_vendor").unwrap_or_default();
    let product = read("/sys/class/dmi/id/product_name").unwrap_or_default();
    let hypervisor = std::fs::read_to_string("/proc/cpuinfo").is_ok_and(|c| c.lines().any(|l| l.starts_with("flags") && l.contains(" hypervisor")));
    if vendor == "QEMU" || product.contains("KVM") || product.contains("Virtual") || hypervisor {
        return ("vm", if vendor.is_empty() { "hypervisor".into() } else { vendor.to_lowercase() });
    }
    if !vendor.is_empty() {
        return ("machine", String::new());
    }
    ("unknown", String::new())
}

fn count(dir: &str, keep: impl Fn(&Path) -> bool) -> usize {
    std::fs::read_dir(dir).map(|d| d.flatten().filter(|e| keep(&e.path())).count()).unwrap_or(0)
}

fn tool(name: &str) -> bool {
    ["/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin"].iter().any(|d| Path::new(d).join(name).exists())
}

pub fn whoami(started_unix: u64) -> String {
    let (id, version, pretty) = os_release();
    let (kind, detail) = kind();
    let uid = unsafe { libc::geteuid() };
    let bool_ = |b: bool| if b { "true".to_string() } else { "false".to_string() };
    let host = read("/proc/sys/kernel/hostname").unwrap_or_default();
    let arch = std::env::consts::ARCH;
    let bluetooth = count("/sys/class/bluetooth", |p| p.file_name().is_some_and(|n| n.to_string_lossy().starts_with("hci")));
    let wireless = count("/sys/class/net", |p| p.join("wireless").exists() || p.join("phy80211").exists());
    let sees = object(&[
        ("bluetooth_controllers", bluetooth.to_string()),
        ("wireless_interfaces", wireless.to_string()),
        ("system_bus", bool_(Path::new("/run/dbus/system_bus_socket").exists())),
        ("rfkill", bool_(std::fs::File::open("/dev/rfkill").is_ok())),
        ("bluetoothctl", bool_(tool("bluetoothctl"))),
        ("iw", bool_(tool("iw"))),
        ("nmcli", bool_(tool("nmcli"))),
    ]);
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    object(&[
        ("product", string("ringmaster-engine")),
        ("version", string(crate::VERSION)),
        ("api", crate::API.to_string()),
        ("probe_sha256", string(&crate::probe::script_sha256())),
        ("host", string(&host)),
        ("os_id", string(&id)),
        ("os_version", string(&version)),
        ("os_name", string(&pretty)),
        ("kind", string(kind)),
        ("kind_detail", string(&detail)),
        ("arch", string(arch)),
        ("uid", uid.to_string()),
        ("root", bool_(uid == 0)),
        ("sees", sees),
        ("started", started_unix.to_string()),
        ("now", now.to_string()),
    ])
}
