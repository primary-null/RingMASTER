# Wi-Fi and radio samples

Text the Wi-Fi and radio parsers are tested against. Every name is invented (`Example Home`, `Sample Guest`...) and every
address is locally administered (`02:00:00:...`), so nothing here identifies a place or a person.

| File | What it is |
| --- | --- |
| `nmcli-device-status.txt`, `nmcli-wifi-list.txt`, `nmcli-wifi-list-old.txt` | NetworkManager terse output (`--terse --escape yes`), written to the documented format: colons inside a value are `\:`. `-old` is a version without the BANDWIDTH column. |
| `iw-dev.txt`, `iw-link-*.txt`, `iw-scan.txt` | `iw dev`, `iw dev <if> link`, `iw dev <if> scan`, written to the format iw 6.x prints. |
| `proc-net-wireless.txt`, `rfkill-list.txt` | The kernel's and util-linux's own formats. |
| `bluetoothctl-*.txt`, `hciconfig.txt`, `hcitool-dev.txt`, `btmgmt-info.txt` | BlueZ tool output, written to the formats BlueZ 5.7x prints. |
| `probe-constructed-tree.txt`, `wifi-constructed-tree.txt` | Real output of `radio/probe.sh` and `radio/wifi-scan.sh` run under `dash` against a constructed sysfs tree (PCI Wi-Fi card, a radio without an interface, a USB Bluetooth controller, soft-blocked Bluetooth). The random token is kept as it was. |
| `real-fedora-*-no-radio.txt`, `real-wsl-*-no-radio.txt` | Real output of both scripts on a Fedora 45 cloud VM (NetworkManager and rfkill installed, no radio) and on Ubuntu 26.04 under WSL2 (no tools, no radio), as an ordinary user and as root. |
| `windows-location-off.jsonl` | A capture of `wifi/wifi-scan.ps1` on a Windows 11 computer with Location turned off, with the adapter name, address, GUID and PnP id replaced by invented ones. The timings and the Windows answers (`DeniedBySystem`, channel, signal) are as captured. |
| `windows-scan-allowed.jsonl` | The lines the script prints when Windows allows scanning. Not a hardware capture: Location was off on the machine used, so this is written to the script's own output format and the WinRT property names (checked by reflection). |

The tool outputs above are not hardware captures from a live radio: the lab has no Wi-Fi adapter. Where a parser reads a
format, the fixture follows the tool's documented or published output.
