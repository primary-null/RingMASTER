//! ringmaster-notify: RingMASTER's Windows notification helper.
//!
//! Shows real Windows app notifications (WinRT `ToastNotificationManager`) under RingMASTER's own
//! AppUserModelID. It never changes notification settings, Focus Assist or Do Not Disturb, and never raises a
//! notification above the normal scenario, so Windows' own quiet rules apply.
//!
//! Identity: Microsoft's documented rule for a desktop app without a package is that toasts need a Start menu
//! shortcut that carries the app's AppUserModelID. The MSI installs RingMASTER's shortcut; `identity` with
//! `write:true` adds the ID to that shortcut when it has none. It only touches shortcuts that point at the
//! installed RingMASTER launcher (`--exe`) and never replaces a different ID.
//!
//! Protocol: one compact JSON object per line on stdin, one per line on stdout. Requests carry a `seq` that the
//! matching `result` repeats. The first output line is `{"t":"hello","v":".."}`.
//!   {"op":"identity","seq":1,"write":false}   -> {"t":"result","seq":1,"identity":"present|registered|missing|conflict|no_shortcut|error",..}
//!   {"op":"status","seq":2}                   -> {"t":"result","seq":2,"setting":"enabled|disabled_app|disabled_user|disabled_policy|disabled_manifest|unknown"}
//!   {"op":"show","seq":3,"handle":"n7","tag":"<hex>","title":"..","body":".."}
//!                                             -> {"t":"result","seq":3,"outcome":"sent|blocked|unsupported|failed","detail":".."}
//!   {"op":"withdraw","seq":4,"tag":"<hex>"}   -> {"t":"result","seq":4,"outcome":"withdrawn|failed"}
//! Events: {"t":"activated","handle":"n7"} when the person selects a notification this process showed.
//! The helper exits when stdin closes. Text is placed with the XML DOM (text nodes and attributes), never by
//! building XML strings, so no input can change the notification's structure.
//!
//! Usage: ringmaster-notify --aumid RingMASTER.Desktop --exe C:\path\to\RingMASTER.exe

use serde_json::{Value, json};
use std::collections::VecDeque;
use std::io::{BufRead, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::mpsc;
use std::time::Duration;
use windows::Data::Xml::Dom::XmlDocument;
use windows::Foundation::TypedEventHandler;
use windows::UI::Notifications::{NotificationSetting, ToastFailedEventArgs, ToastNotification, ToastNotificationManager};
use windows::Win32::Foundation::{BOOL, HANDLE};
use windows::Win32::Storage::EnhancedStorage::PKEY_AppUserModel_ID;
use windows::Win32::System::Com::StructuredStorage::{PVCHF_DEFAULT, PropVariantChangeType};
use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance, CoTaskMemFree, IPersistFile, STGM_READ, STGM_READWRITE};
use windows::Win32::System::Variant::VT_LPWSTR;
use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize};
use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
use windows::Win32::UI::Shell::{FOLDERID_Programs, IShellLinkW, KF_FLAG_DEFAULT, SHGetKnownFolderPath, ShellLink,
    SHChangeNotify, SHCNE_UPDATEITEM, SHCNF_PATHW, SetCurrentProcessExplicitAppUserModelID};
use windows::core::{BSTR, HSTRING, IInspectable, Interface, PCWSTR, PROPVARIANT};

const GROUP: &str = "ringmaster";
const MAX_LINE: usize = 16 * 1024;
const MAX_TITLE: usize = 200;
const MAX_BODY: usize = 1200;
const KEEP_TOASTS: usize = 64;
const FAIL_WAIT: Duration = Duration::from_millis(900);
const MAX_LINKS: usize = 4000;

static OUT: Mutex<()> = Mutex::new(());
/// Shown toasts stay referenced so their Activated handlers keep working; the oldest are dropped first.
static TOASTS: Mutex<VecDeque<(String, ToastNotification)>> = Mutex::new(VecDeque::new());

fn emit(value: &Value) {
    let _guard = OUT.lock().unwrap_or_else(|e| e.into_inner());
    let mut out = std::io::stdout().lock();
    // A closed pipe means the app stopped listening; exit quietly.
    if writeln!(out, "{value}").and_then(|_| out.flush()).is_err() {
        std::process::exit(0);
    }
}

fn hello_line() -> Value {
    json!({ "t": "hello", "v": env!("CARGO_PKG_VERSION") })
}

struct Args {
    aumid: String,
    exe: PathBuf,
}

/// AppUserModelID rules: at most 128 characters, no spaces. This helper accepts only a conservative subset.
fn valid_aumid(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_')
        && !id.starts_with('.') && !id.ends_with('.')
}

fn valid_handle(handle: &str) -> bool {
    handle.len() >= 2 && handle.len() <= 16 && handle.starts_with('n') && handle[1..].chars().all(|c| c.is_ascii_digit())
}

/// Tags are produced by the app as lowercase hex digests; Windows allows 64 characters since build 15063.
fn valid_tag(tag: &str) -> bool {
    !tag.is_empty() && tag.len() <= 64 && tag.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c))
}

fn parse_args(args: &[String]) -> Option<Args> {
    let mut aumid = None;
    let mut exe = None;
    let mut i = 1;
    while i + 1 < args.len() {
        match args[i].as_str() {
            "--aumid" => aumid = Some(args[i + 1].clone()),
            "--exe" => exe = Some(PathBuf::from(&args[i + 1])),
            _ => return None,
        }
        i += 2;
    }
    if i != args.len() {
        return None;
    }
    let aumid = aumid.filter(|a| valid_aumid(a))?;
    let exe = exe.filter(|e| e.is_absolute())?;
    Some(Args { aumid, exe })
}

/// Characters a notification may show: no control characters except line breaks, no XML-illegal code points.
fn clean_text(text: &str, max_chars: usize) -> String {
    text.chars()
        .filter(|&c| c == '\n' || !(c.is_control() || c == '\u{FFFE}' || c == '\u{FFFF}'))
        .take(max_chars)
        .collect()
}

/// Comparable form of a file path: backslashes, no trailing separator, case-folded (NTFS paths are case-insensitive).
fn path_key(path: &str) -> String {
    let p = path.trim().replace('/', "\\");
    let p = p.strip_prefix("\\\\?\\").unwrap_or(&p);
    p.trim_end_matches('\\').to_lowercase()
}

fn same_file(a: &str, b: &Path) -> bool {
    if path_key(a) == path_key(&b.to_string_lossy()) {
        return true;
    }
    match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
        (Ok(x), Ok(y)) => path_key(&x.to_string_lossy()) == path_key(&y.to_string_lossy()),
        _ => false,
    }
}

fn programs_dir() -> Option<PathBuf> {
    // SAFETY: the returned buffer is owned by us and released with CoTaskMemFree after copying.
    unsafe {
        let raw = SHGetKnownFolderPath(&FOLDERID_Programs, KF_FLAG_DEFAULT, HANDLE::default()).ok()?;
        let text = raw.to_string().ok();
        CoTaskMemFree(Some(raw.0 as *const _));
        text.map(PathBuf::from)
    }
}

/// .lnk files under the user's Start menu Programs folder, at most four levels deep and [MAX_LINKS] entries.
fn find_links(root: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let mut stack = vec![(root.to_path_buf(), 0usize)];
    let mut seen = 0usize;
    while let Some((dir, depth)) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for entry in entries.flatten() {
            seen += 1;
            if seen > MAX_LINKS {
                return found;
            }
            let Ok(kind) = entry.file_type() else { continue };
            let path = entry.path();
            if kind.is_dir() && depth < 4 {
                stack.push((path, depth + 1));
            } else if kind.is_file() && path.extension().map(|e| e.eq_ignore_ascii_case("lnk")).unwrap_or(false) {
                found.push(path);
            }
        }
    }
    found
}

struct Link {
    link: IShellLinkW,
    file: IPersistFile,
}

fn open_link(path: &Path, write: bool) -> windows::core::Result<Link> {
    // SAFETY: plain COM calls on objects this function owns.
    unsafe {
        let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
        let file: IPersistFile = link.cast()?;
        file.Load(&HSTRING::from(path.as_os_str()), if write { STGM_READWRITE } else { STGM_READ })?;
        Ok(Link { link, file })
    }
}

impl Link {
    fn target(&self) -> String {
        let mut buf = [0u16; 2048];
        // SAFETY: the buffer outlives the call; a null find-data pointer is allowed.
        unsafe {
            if self.link.GetPath(&mut buf, std::ptr::null_mut(), 0).is_err() {
                return String::new();
            }
        }
        let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
        String::from_utf16_lossy(&buf[..end])
    }

    fn app_id(&self) -> windows::core::Result<String> {
        let store: IPropertyStore = self.link.cast()?;
        // SAFETY: PROPVARIANT is cleared on drop.
        let value = unsafe { store.GetValue(&PKEY_AppUserModel_ID)? };
        if value.is_empty() {
            return Ok(String::new());
        }
        Ok(BSTR::try_from(&value).map(|b| b.to_string()).unwrap_or_default())
    }

    fn set_app_id(&self, id: &str) -> windows::core::Result<()> {
        let store: IPropertyStore = self.link.cast()?;
        let source = PROPVARIANT::from(id);
        let mut wide = PROPVARIANT::default();
        // SAFETY: both variants are valid; System.AppUserModel.ID is documented as a VT_LPWSTR string.
        unsafe {
            PropVariantChangeType(&mut wide, &source, PVCHF_DEFAULT, VT_LPWSTR)?;
            store.SetValue(&PKEY_AppUserModel_ID, &wide)?;
            store.Commit()?;
            self.file.Save(PCWSTR::null(), BOOL::from(true))?;
        }
        Ok(())
    }
}

fn identity(args: &Args, write: bool) -> Value {
    let Some(root) = programs_dir() else {
        return json!({ "identity": "error", "detail": "Start menu folder not found" });
    };
    let mut matched = 0;
    let mut missing: Vec<PathBuf> = Vec::new();
    let mut conflict = false;
    for path in find_links(&root) {
        let Ok(link) = open_link(&path, false) else { continue };
        if !same_file(&link.target(), &args.exe) {
            continue;
        }
        matched += 1;
        match link.app_id() {
            Ok(id) if id == args.aumid => {
                notify_shortcut(&path);
                return json!({ "identity": "present" });
            },
            Ok(id) if id.is_empty() => missing.push(path),
            Ok(_) => conflict = true,
            Err(_) => {}
        }
    }
    if matched == 0 {
        return json!({ "identity": "no_shortcut" });
    }
    if missing.is_empty() {
        return json!({ "identity": if conflict { "conflict" } else { "error" } });
    }
    if !write {
        return json!({ "identity": "missing" });
    }
    let mut last_error = String::new();
    for path in &missing {
        match open_link(path, true).and_then(|link| link.set_app_id(&args.aumid)) {
            Ok(()) => {
                notify_shortcut(path);
                return json!({ "identity": "registered" });
            },
            Err(e) => last_error = format!("0x{:08X}", e.code().0 as u32),
        }
    }
    json!({ "identity": "error", "detail": last_error })
}

fn notify_shortcut(path: &Path) {
    let wide = HSTRING::from(path.as_os_str());
    // Inform the shell after changing shortcut properties; do not restart Explorer or change OS preferences.
    unsafe { SHChangeNotify(SHCNE_UPDATEITEM, SHCNF_PATHW, Some(wide.as_ptr().cast()), None); }
}

fn setting_name(setting: NotificationSetting) -> &'static str {
    match setting {
        NotificationSetting::Enabled => "enabled",
        NotificationSetting::DisabledForApplication => "disabled_app",
        NotificationSetting::DisabledForUser => "disabled_user",
        NotificationSetting::DisabledByGroupPolicy => "disabled_policy",
        NotificationSetting::DisabledByManifest => "disabled_manifest",
        _ => "unknown",
    }
}

fn status(args: &Args) -> Value {
    let notifier = ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(&args.aumid));
    match notifier.and_then(|n| n.Setting()) {
        Ok(s) => json!({ "setting": setting_name(s) }),
        Err(e) => json!({ "setting": "unknown", "detail": format!("0x{:08X}", e.code().0 as u32) }),
    }
}

/// `<toast launch="n7"><visual><binding template="ToastGeneric"><text>title</text><text>body</text>..`
fn toast_document(handle: &str, title: &str, body: &str) -> windows::core::Result<XmlDocument> {
    let doc = XmlDocument::new()?;
    let toast = doc.CreateElement(&HSTRING::from("toast"))?;
    toast.SetAttribute(&HSTRING::from("launch"), &HSTRING::from(handle))?;
    let visual = doc.CreateElement(&HSTRING::from("visual"))?;
    let binding = doc.CreateElement(&HSTRING::from("binding"))?;
    binding.SetAttribute(&HSTRING::from("template"), &HSTRING::from("ToastGeneric"))?;
    for text in [title, body] {
        if text.is_empty() {
            continue;
        }
        let element = doc.CreateElement(&HSTRING::from("text"))?;
        element.AppendChild(&doc.CreateTextNode(&HSTRING::from(text))?)?;
        binding.AppendChild(&element)?;
    }
    visual.AppendChild(&binding)?;
    toast.AppendChild(&visual)?;
    doc.AppendChild(&toast)?;
    Ok(doc)
}

fn hresult(e: &windows::core::Error) -> String {
    format!("0x{:08X}", e.code().0 as u32)
}

fn show(args: &Args, request: &Value) -> Value {
    let handle = request["handle"].as_str().unwrap_or_default();
    let tag = request["tag"].as_str().unwrap_or_default();
    if !valid_handle(handle) || !valid_tag(tag) {
        return json!({ "outcome": "failed", "detail": "bad request" });
    }
    let title = clean_text(request["title"].as_str().unwrap_or_default(), MAX_TITLE);
    let body = clean_text(request["body"].as_str().unwrap_or_default(), MAX_BODY);
    if title.trim().is_empty() {
        return json!({ "outcome": "failed", "detail": "empty title" });
    }
    let notifier = match ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(&args.aumid)) {
        Ok(n) => n,
        Err(e) => return json!({ "outcome": "unsupported", "detail": hresult(&e) }),
    };
    match notifier.Setting() {
        Ok(NotificationSetting::Enabled) => {}
        Ok(other) => return json!({ "outcome": "blocked", "detail": setting_name(other) }),
        // An unpackaged app has no per-app settings entry until its first Show. ERROR_NOT_FOUND is not
        // an opt-out: let Show initialize it. Windows still enforces global, policy and user quiet settings.
        Err(e) if e.code().0 as u32 == 0x80070490 => {}
        Err(e) => return json!({ "outcome": "unsupported", "detail": hresult(&e) }),
    }
    let built = toast_document(handle, &title, &body).and_then(|doc| ToastNotification::CreateToastNotification(&doc));
    let toast = match built {
        Ok(t) => t,
        Err(e) => return json!({ "outcome": "failed", "detail": hresult(&e) }),
    };
    if let Err(e) = toast.SetTag(&HSTRING::from(tag)).and_then(|_| toast.SetGroup(&HSTRING::from(GROUP))) {
        return json!({ "outcome": "failed", "detail": hresult(&e) });
    }
    let (failed_tx, failed_rx) = mpsc::channel::<String>();
    let failed_handle = handle.to_string();
    let failed = toast.Failed(&TypedEventHandler::<ToastNotification, ToastFailedEventArgs>::new(move |_, args| {
        let code = args.as_ref().and_then(|a| a.ErrorCode().ok()).map(|c| format!("0x{:08X}", c.0 as u32)).unwrap_or_default();
        // A late failure (after the result was sent) is still reported as an event.
        if failed_tx.send(code.clone()).is_err() {
            emit(&json!({ "t": "failed", "handle": failed_handle, "detail": code }));
        }
        Ok(())
    }));
    let activated_handle = handle.to_string();
    let activated = toast.Activated(&TypedEventHandler::<ToastNotification, IInspectable>::new(move |_, _| {
        emit(&json!({ "t": "activated", "handle": activated_handle }));
        Ok(())
    }));
    // Dismissal (timed out or closed by the person) is deliberately not reported: it must never acknowledge anything.
    if let Err(e) = failed.and(activated) {
        return json!({ "outcome": "failed", "detail": hresult(&e) });
    }
    if let Err(e) = notifier.Show(&toast) {
        return json!({ "outcome": "failed", "detail": hresult(&e) });
    }
    {
        let mut kept = TOASTS.lock().unwrap_or_else(|e| e.into_inner());
        kept.push_back((handle.to_string(), toast));
        while kept.len() > KEEP_TOASTS {
            kept.pop_front();
        }
    }
    // Windows reports a refused toast through Failed shortly after Show. No failure within the wait means Windows
    // accepted it; whether a banner appears is Windows' decision (Focus Assist / Do not disturb may hold it).
    match failed_rx.recv_timeout(FAIL_WAIT) {
        Ok(code) => json!({ "outcome": "failed", "detail": code }),
        Err(_) => json!({ "outcome": "sent" }),
    }
}

fn withdraw(args: &Args, request: &Value) -> Value {
    let tag = request["tag"].as_str().unwrap_or_default();
    if !valid_tag(tag) {
        return json!({ "outcome": "failed", "detail": "bad request" });
    }
    let removed = ToastNotificationManager::History()
        .and_then(|h| h.RemoveGroupedTagWithId(&HSTRING::from(tag), &HSTRING::from(GROUP), &HSTRING::from(&args.aumid)));
    match removed {
        Ok(()) => json!({ "outcome": "withdrawn" }),
        Err(e) => json!({ "outcome": "failed", "detail": hresult(&e) }),
    }
}

fn handle_request(args: &Args, request: &Value) -> Option<Value> {
    let seq = request["seq"].as_u64()?;
    let mut reply = match request["op"].as_str()? {
        "identity" => identity(args, request["write"].as_bool().unwrap_or(false)),
        "status" => status(args),
        "show" => show(args, request),
        "withdraw" => withdraw(args, request),
        _ => json!({ "outcome": "failed", "detail": "unknown op" }),
    };
    reply["t"] = json!("result");
    reply["seq"] = json!(seq);
    Some(reply)
}

/// Reads one line of at most [MAX_LINE] bytes. Longer lines are skipped whole. None at end of input.
fn read_bounded_line(input: &mut impl BufRead) -> Option<Option<Vec<u8>>> {
    let mut line = Vec::new();
    let mut overflow = false;
    loop {
        let buf = match input.fill_buf() {
            Ok(b) => b,
            Err(_) => return None,
        };
        if buf.is_empty() {
            return if line.is_empty() && !overflow { None } else { Some(if overflow { None } else { Some(line) }) };
        }
        let (chunk, done) = match buf.iter().position(|&b| b == b'\n') {
            Some(i) => (&buf[..i], Some(i + 1)),
            None => (buf, None),
        };
        if !overflow {
            if line.len() + chunk.len() > MAX_LINE {
                overflow = true;
                line.clear();
            } else {
                line.extend_from_slice(chunk);
            }
        }
        let used = done.unwrap_or(buf.len());
        input.consume(used);
        if done.is_some() {
            return Some(if overflow { None } else { Some(line) });
        }
    }
}

fn main() {
    let raw: Vec<String> = std::env::args().collect();
    let Some(args) = parse_args(&raw) else {
        eprintln!("ringmaster-notify: usage: --aumid <id> --exe <absolute path>");
        std::process::exit(2);
    };
    // SAFETY: called once on the main thread before any WinRT use; the multithreaded apartment lets toast events
    // arrive on pool threads while the main thread reads requests.
    unsafe {
        let _ = RoInitialize(RO_INIT_MULTITHREADED);
        let _ = SetCurrentProcessExplicitAppUserModelID(&HSTRING::from(&args.aumid));
    }
    emit(&hello_line());
    let stdin = std::io::stdin();
    let mut input = stdin.lock();
    while let Some(line) = read_bounded_line(&mut input) {
        let Some(bytes) = line else {
            emit(&json!({ "t": "error", "detail": "line too long" }));
            continue;
        };
        let decoded = String::from_utf8_lossy(&bytes);
        let text = decoded.trim_start_matches('\u{FEFF}');
        if text.trim().is_empty() {
            continue;
        }
        match serde_json::from_str::<Value>(text).ok().and_then(|r| handle_request(&args, &r)) {
            Some(reply) => emit(&reply),
            None => emit(&json!({ "t": "error", "detail": "bad request" })),
        }
    }
    std::process::exit(0);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn args_need_a_valid_id_and_an_absolute_exe() {
        let ok = parse_args(&strings(&["x", "--aumid", "RingMASTER.Desktop", "--exe", "C:\\Apps\\RingMASTER\\RingMASTER.exe"]));
        assert_eq!(ok.unwrap().aumid, "RingMASTER.Desktop");
        assert!(parse_args(&strings(&["x", "--aumid", "Ring MASTER", "--exe", "C:\\a.exe"])).is_none());
        assert!(parse_args(&strings(&["x", "--aumid", "RingMASTER.Desktop", "--exe", "a.exe"])).is_none());
        assert!(parse_args(&strings(&["x", "--aumid", "RingMASTER.Desktop"])).is_none());
        assert!(parse_args(&strings(&["x", "--aumid", "a", "--exe", "C:\\a.exe", "--extra"])).is_none());
    }

    #[test]
    fn handles_and_tags_are_strict() {
        assert!(valid_handle("n7"));
        assert!(valid_handle("n123456789012"));
        assert!(!valid_handle("n"));
        assert!(!valid_handle("x7"));
        assert!(!valid_handle("n7&"));
        assert!(valid_tag("0123abcdef"));
        assert!(!valid_tag("ABC"));
        assert!(!valid_tag(""));
        assert!(!valid_tag(&"a".repeat(65)));
    }

    #[test]
    fn text_drops_controls_and_is_bounded() {
        assert_eq!(clean_text("a\u{0007}b\nc\u{FFFF}", 10), "ab\nc");
        assert_eq!(clean_text("abcdef", 3), "abc");
        assert_eq!(clean_text("<b>&amp;</b>", 20), "<b>&amp;</b>");
    }

    #[test]
    fn paths_compare_case_insensitively() {
        assert_eq!(path_key("C:/Example/Apps/RingMASTER.exe"), path_key("c:\\example\\apps\\ringmaster.EXE"));
        assert_eq!(path_key("\\\\?\\C:\\X\\"), "c:\\x");
    }

    #[test]
    fn bounded_lines_skip_overlong_input() {
        let long = "x".repeat(MAX_LINE + 5);
        let data = format!("{{\"a\":1}}\n{long}\n{{\"b\":2}}");
        let mut input = std::io::Cursor::new(data.into_bytes());
        assert_eq!(read_bounded_line(&mut input), Some(Some(b"{\"a\":1}".to_vec())));
        assert_eq!(read_bounded_line(&mut input), Some(None));
        assert_eq!(read_bounded_line(&mut input), Some(Some(b"{\"b\":2}".to_vec())));
        assert_eq!(read_bounded_line(&mut input), None);
    }

    #[test]
    fn hello_names_a_version() {
        assert_eq!(hello_line()["t"], "hello");
    }
}
