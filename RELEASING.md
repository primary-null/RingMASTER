# Releasing RingMASTER

Releases are built by GitHub Actions from a version tag. The update feed is signed offline with a key that never goes to GitHub. The app is Apache-2.0.

## Files in a release

| Platform id | File | Built on |
|---|---|---|
| `windows-x64-msi` | `RingMASTER-<version>.msi` | GitHub's Windows runner |
| `debian-13-amd64-deb` | `ringmaster-<version>-debian-13-amd64.deb` | a Debian 13 container |
| `ubuntu-26.04-amd64-deb` | `ringmaster-<version>-ubuntu-26.04-amd64.deb` | an Ubuntu 26.04 container |
| `fedora-45-x86_64-rpm` | `ringmaster-<version>-fedora-45-x86_64.rpm` | a Fedora 45 container |
| `arch-x86_64-pkg` | `ringmaster-<version>-arch-x86_64.pkg.tar.zst` | an Arch Linux container |

Plus `SHA256SUMS`, `ringmaster-update.json` and `ringmaster-update.json.sig`.

Linux packages are built on their own distribution because jpackage writes the build system's package names into Depends/Requires (Debian `libjpeg62-turbo`, Ubuntu `libjpeg-turbo8`, Fedora `libglvnd-glx`). Arch wraps the app image with a PKGBUILD. Debian and Ubuntu use `amd64` in the file name; Fedora and Arch use `x86_64`. The packages are not code-signed or GPG-signed. Integrity comes from `SHA256SUMS`, the build attestations and the signed update feed. Each Linux desktop entry is named RingMASTER, ends its Categories line with a semicolon, and sets StartupWMClass to `local-ringleader-desktop-MainKt`.

## Steps

1. Raise the version in the root `build.gradle.kts`. Windows Installer compares only the first three numeric fields, so change at least one of them every release. A fourth field alone is not an update.
2. Commit, then tag and push the tag: `git tag v<version>` and `git push origin v<version>`. The tag must match the version in `build.gradle.kts`, or the workflow stops.
3. `.github/workflows/release.yml` runs the tests on Windows, Debian, Ubuntu, Fedora and Arch, builds the five packages, writes `SHA256SUMS`, records a build provenance attestation for each file and creates a **draft** release. (Run the workflow by hand from the Actions tab to build without releasing.)
4. Download the draft's files, install each package on its system and check that it starts and connects.
5. Write the update manifest with the real sizes and hashes, sign it offline and verify it (below).
6. Upload `ringmaster-update.json` and `ringmaster-update.json.sig` to the draft, replace the draft text with the release notes, and publish it as the latest release, not a pre-release: the app reads the feed from the latest release.

Checking a published file:

```sh
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify RingMASTER-<version>.msi --repo primary-null/RingMASTER
```

## The signing key

Use JDK 21. Keep the private key offline, outside the repository and outside CI. Only the public key (SPKI, base64) goes into `ReleaseKeys.publicKeys`, a list so that keys can be rotated. An empty list turns network checks off.

Commands (PowerShell or cmd; one Gradle property per argument, quoted when a path contains spaces):

```powershell
.\gradlew.bat -q :core:releaseKey -Prelease=generate "-PreleaseKey=E:\offline\ringmaster-private.pem"
.\gradlew.bat -q :core:releaseKey -Prelease=sign "-PreleaseKey=E:\offline\ringmaster-private.pem" "-PreleaseManifest=C:\release\ringmaster-update.json"
.\gradlew.bat -q :core:releaseKey -Prelease=verify -PreleasePublic=PUBLIC_SPKI_BASE64 "-PreleaseManifest=C:\release\ringmaster-update.json"
```

Generate prints only the public key and refuses to overwrite an existing private key file. Sign writes `ringmaster-update.json.sig` (base64, detached) next to the manifest and prints nothing. Verify exits unsuccessfully if the signature does not match or the manifest is not one the app would accept. On success it prints the version, the published time and the platform ids. Never put the private key or its contents in commands, logs, screenshots, source or CI variables.

## The manifest

UTF-8 JSON: schema 1, channel `stable`, a numeric dotted version, an ISO-8601 UTC `published` time, plain-text `notes` of at most 8000 characters, an optional HTTPS `notesUrl` and an optional numeric `minVersion`. Notes may contain line breaks (`\n` in the JSON string); any other control character, anywhere in the file, is refused. Every URL must use HTTPS without user-info. Each asset needs its platform id, a 64-hex SHA-256 and a size between 1048576 and 1073741824 bytes inclusive. A platform id is 1 to 64 characters matching `^[a-z0-9][a-z0-9_]*(?:[.-][a-z0-9_]+)*$`. Well-formed ids this build does not ship are still checked (URL, SHA-256, size) and then skipped. Clients from 0.23.3 do that so a newer manifest can list extra platforms. Installs of 0.22.0–0.23.2 refuse the whole file if it lists an unknown id, including Fedora or Arch; those installs need a manual upgrade. They report "The update feed could not be verified." A malformed id, a duplicate id, or an unknown asset that fails the URL or size rules still refuses the whole file.

```json
{"schema":1,"channel":"stable","version":"0.24.0","published":"2026-12-01T12:00:00Z","notes":"Release notes in plain text.","notesUrl":"https://github.com/primary-null/RingMASTER/releases/tag/v0.24.0","assets":[{"platform":"windows-x64-msi","url":"https://github.com/primary-null/RingMASTER/releases/download/v0.24.0/RingMASTER-0.24.0.msi","sha256":"REPLACE_WITH_64_HEX_DIGITS","size":1048576}]}
```

The `published` timestamp must strictly increase with each signed manifest. The app remembers the most recent accepted value and rejects any manifest whose `published` is earlier.

List all five assets. Take hashes and sizes from the draft's files, sign the exact final bytes, and sign again after any edit. Never replace a file in a published release: the signed feed pins its hash. Fix a broken package in a new version.

## What the app does with it

The app reads `https://github.com/primary-null/RingMASTER/releases/latest/download/ringmaster-update.json` and the `.sig` next to it, following HTTPS redirects only and without the GitHub API. A check normally sends two GETs plus redirects, with `User-Agent: RingMASTER/<version>` and ordinary HTTP/TLS metadata: no account, token, profile, cookies, telemetry identifier or server details. Limits: 256 KB manifest, 1 KB signature, 10 s to connect, 20 s to read. A 404 for the manifest means nothing is published yet.

No downgrade is offered. `minVersion` makes older installs go through an intermediate release instead of jumping straight to an incompatible one. Checks and their consent prompt never run in browser mode, and demo sessions use the same consent. Packagers can set `RINGMASTER_DISABLE_UPDATES=1` to turn checks and prompts off. The app only opens the release page; it never downloads or installs packages itself.
