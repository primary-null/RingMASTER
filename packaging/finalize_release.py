"""Verify and optionally publish a complete, offline-signed RingMASTER release.

Requires Python 3.11+, JDK 21 and an authenticated GitHub CLI. No private signing key
is read by this tool. Without --publish it performs verification only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PLATFORMS = {"windows-x64-msi", "debian-13-amd64-deb", "ubuntu-26.04-amd64-deb",
             "fedora-45-x86_64-rpm", "arch-x86_64-pkg"}


def command(*args: str) -> str:
    result = subprocess.run(list(args), cwd=ROOT, check=True, capture_output=True, text=True)
    return result.stdout


def api(repo: str, path: str, *, payload: dict | None = None) -> dict:
    args = ["gh", "api", f"repos/{repo}/{path}"]
    if payload is None:
        return json.loads(command(*args))
    result = subprocess.run([*args, "--method", "PATCH", "--input", "-"], cwd=ROOT,
                            input=json.dumps(payload), check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_signature(manifest: Path) -> None:
    keys = re.findall(r'"(MCow[A-Za-z0-9+/=]+)"',
                      (ROOT / "core/src/main/kotlin/local/ringleader/core/ReleaseKeys.kt").read_text(encoding="utf-8"))
    java = shutil.which("java")
    if not java or not keys:
        raise ValueError("JDK 21 and a configured public update key are required")
    for key in keys:
        try:
            # Invoke Java directly, not a shell or .bat: paths and Gradle arguments stay literal.
            command(java, "-classpath", str(ROOT / "gradle/wrapper/gradle-wrapper.jar"),
                    "org.gradle.wrapper.GradleWrapperMain", "-q", ":core:releaseKey",
                    "-Prelease=verify", "-PreleasePublic=" + key,
                    "-PreleaseManifest=" + str(manifest))
            return
        except subprocess.CalledProcessError:
            continue
    raise ValueError("The released app's verifier rejected the manifest or signature")


def verify_manifest_assets(manifest: dict, release: dict, repo: str, tag: str) -> list[dict]:
    assets = manifest["assets"]
    if manifest["version"] != tag[1:] or {a["platform"] for a in assets} != PLATFORMS or len(assets) != len(PLATFORMS):
        raise ValueError("Manifest version or platform set does not match this release")
    if manifest.get("notesUrl") != f"https://github.com/{repo}/releases/tag/{tag}":
        raise ValueError("Manifest release-notes URL points elsewhere")
    existing = {a["name"]: a for a in release["assets"]}
    prefix = f"https://github.com/{repo}/releases/download/{tag}/"
    checked = []
    for item in assets:
        if not item["url"].startswith(prefix):
            raise ValueError("An installer URL does not belong to this exact release")
        name = item["url"][len(prefix):]
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name not in existing:
            raise ValueError("An installer is absent from the release")
        remote = existing[name]
        if remote["size"] != item["size"] or remote.get("digest") != "sha256:" + item["sha256"]:
            raise ValueError("Installer size/digest differs from the signed manifest")
        checked.append({**item, "name": name})
    return checked


def run(args: argparse.Namespace) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        raise ValueError("Expected OWNER/REPOSITORY")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", args.tag):
        raise ValueError("Expected a vMAJOR.MINOR.PATCH version tag")
    source_version = re.search(r'version = "([0-9.]+)"', (ROOT / "build.gradle.kts").read_text())[1]
    if source_version != args.tag[1:]:
        raise ValueError("Use a checkout of the version being finalized")
    release = api(args.repo, "releases/tags/" + args.tag)
    if not release["draft"] and args.publish:
        raise ValueError("Already published; this tool never replaces a published asset")
    ref = api(args.repo, "git/ref/tags/" + args.tag)["object"]
    if ref["type"] == "tag":
        ref = api(args.repo, "git/tags/" + ref["sha"])["object"]
    if ref["type"] != "commit":
        raise ValueError("Tag does not resolve to a source commit")
    manifest_path = args.manifest.resolve()
    if manifest_path.name != "ringmaster-update.json":
        raise ValueError("The manifest must be named ringmaster-update.json")
    signature_path = Path(str(manifest_path) + ".sig")
    if manifest_path.stat().st_size > 256 * 1024 or signature_path.stat().st_size > 1024:
        raise ValueError("Manifest/signature exceeds the app's size limits")
    verify_signature(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    packages = verify_manifest_assets(manifest, release, args.repo, args.tag)
    expected = {p["name"] for p in packages} | {"SHA256SUMS"}
    feed_names = {"ringmaster-update.json", "ringmaster-update.json.sig"}
    if not expected.issubset({a["name"] for a in release["assets"]}):
        raise ValueError("Release is incomplete")
    if {a["name"] for a in release["assets"]} - expected - feed_names:
        raise ValueError("Unexpected assets require explicit review")
    with tempfile.TemporaryDirectory(prefix="ringmaster-finalize-") as tmp:
        folder = Path(tmp)
        command("gh", "release", "download", args.tag, "--repo", args.repo, "--dir", str(folder))
        for package in packages:
            path = folder / package["name"]
            if digest(path) != package["sha256"] or path.stat().st_size != package["size"]:
                raise ValueError("Downloaded installer differs from the signed manifest")
        sums = {line.split()[1].lstrip("*"): line.split()[0]
                for line in (folder / "SHA256SUMS").read_text().splitlines()}
        for name in expected:
            if name != "SHA256SUMS" and sums.get(name) != digest(folder / name):
                raise ValueError("SHA256SUMS does not match the downloaded installers")
            command("gh", "attestation", "verify", str(folder / name), "--repo", args.repo,
                    "--signer-workflow", args.repo + "/.github/workflows/release.yml",
                    "--source-digest", ref["sha"], "--source-ref", "refs/tags/" + args.tag)
        for name, local in [("ringmaster-update.json", manifest_path), ("ringmaster-update.json.sig", signature_path)]:
            if (folder / name).exists() and (folder / name).read_bytes() != local.read_bytes():
                raise ValueError("Existing feed asset differs; it will not be overwritten")
        if args.publish:
            notes = args.notes.read_text(encoding="utf-8") if args.notes else manifest["notes"]
            if not notes.strip():
                raise ValueError("Release notes must be nonempty")
            for name, local in [("ringmaster-update.json", manifest_path), ("ringmaster-update.json.sig", signature_path)]:
                if not (folder / name).exists():
                    command("gh", "release", "upload", args.tag, str(local), "--repo", args.repo)
            release = api(args.repo, "releases/" + str(release["id"]), payload={
                "draft": False, "prerelease": False, "make_latest": "true", "body": notes})
        proof = []
        if not release["draft"]:
            # Separate opener: no GitHub CLI credentials, cookies or Authorization headers.
            opener = urllib.request.build_opener()
            for package in packages:
                h = hashlib.sha256(); size = 0
                with opener.open(package["url"], timeout=60) as response:
                    for block in iter(lambda: response.read(1024 * 1024), b""):
                        size += len(block); h.update(block)
                if size != package["size"] or h.hexdigest() != package["sha256"]:
                    raise ValueError("Published anonymous download failed verification")
                proof.append({"file": package["name"], "bytes": size, "sha256": h.hexdigest()})
            latest = api(args.repo, "releases/latest")
            if latest["tag_name"] != args.tag:
                raise ValueError("The verified release is not the public latest release")
            for name, local in [("ringmaster-update.json", manifest_path), ("ringmaster-update.json.sig", signature_path)]:
                url = f"https://github.com/{args.repo}/releases/latest/download/{name}"
                with opener.open(url, timeout=60) as response:
                    content = response.read(256 * 1024 + 1)
                if content != local.read_bytes():
                    raise ValueError("The public update feed differs from the verified offline feed")
    return {"release": release["html_url"], "draft": release["draft"], "source": ref["sha"],
            "signature_verified": True, "build_attestations_verified": True,
            "anonymous_installer_downloads": proof}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="primary-null/RingMASTER")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--manifest", type=Path, required=True, help="Offline-signed JSON; .sig must be next to it")
    parser.add_argument("--notes", type=Path, help="Release notes, otherwise the signed manifest's notes")
    parser.add_argument("--publish", action="store_true", help="Finalize after all checks, then verify public downloads")
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    try:
        result = run(args)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit("Release finalization failed: " + str(error)) from error
    text = json.dumps(result, indent=2)
    if args.receipt:
        args.receipt.write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
