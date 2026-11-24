"""Offline regressions for the distribution publication gate."""
import copy
import importlib.util
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
import re
import tempfile
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("finalizer", Path(__file__).with_name("finalize_release.py"))
FINALIZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FINALIZER)


class FinalizeReleaseTest(unittest.TestCase):
    def setUp(self):
        self.repo = "example/RingMASTER"
        self.tag = "v0.25.0"
        self.items = [{"platform": platform, "url": f"https://github.com/{self.repo}/releases/download/{self.tag}/{platform}.pkg",
                       "size": 1048576, "sha256": "a" * 64} for platform in sorted(FINALIZER.PLATFORMS)]
        self.manifest = {"version": "0.25.0", "assets": self.items,
                         "notesUrl": f"https://github.com/{self.repo}/releases/tag/{self.tag}"}
        self.release = {"assets": [{"name": item["url"].rsplit("/", 1)[1], "size": item["size"],
                                    "digest": "sha256:" + item["sha256"]} for item in self.items]}

    def check(self, manifest=None, release=None):
        return FINALIZER.verify_manifest_assets(manifest or self.manifest, release or self.release, self.repo, self.tag)

    def test_complete_release_is_accepted(self):
        self.assertEqual(len(self.check()), 5)

    def test_missing_platform_is_refused(self):
        self.manifest["assets"].pop()
        with self.assertRaises(ValueError): self.check()

    def test_duplicate_platform_is_refused(self):
        self.manifest["assets"][-1] = copy.deepcopy(self.manifest["assets"][0])
        with self.assertRaises(ValueError): self.check()

    def test_foreign_download_is_refused(self):
        self.items[0]["url"] = self.items[0]["url"].replace("example/", "someone-else/")
        with self.assertRaises(ValueError): self.check()

    def test_wrong_version_is_refused(self):
        self.manifest["version"] = "0.24.2"
        with self.assertRaises(ValueError): self.check()

    def test_changed_asset_is_refused(self):
        self.release["assets"][0]["digest"] = "sha256:" + "b" * 64
        with self.assertRaises(ValueError): self.check()

    def test_absent_installer_is_refused(self):
        self.release["assets"].pop()
        with self.assertRaises(ValueError): self.check()

    def source_tag(self):
        """run() insists on a checkout of the version being finalized, so use this checkout's own version."""
        text = (Path(__file__).resolve().parents[1] / "build.gradle.kts").read_text(encoding="utf-8")
        return "v" + re.search(r'version = "([0-9.]+)"', text)[1]

    def test_published_release_is_never_modified(self):
        tag = self.source_tag()
        args = SimpleNamespace(repo=self.repo, tag=tag, publish=True)
        with patch.object(FINALIZER, "api", return_value=[{"tag_name": tag, "draft": False}]) as api:
            with self.assertRaisesRegex(ValueError, "Already published"):
                FINALIZER.run(args)
            api.assert_called_once_with(self.repo, "releases?per_page=100")

    def test_draft_is_found_in_the_release_list(self):
        # GitHub answers 404 to releases/tags/<tag> for a draft; the list includes drafts.
        releases = [{"tag_name": "v0.1.0", "draft": False}, {"tag_name": self.tag, "draft": True, "id": 7}]
        with patch.object(FINALIZER, "api", return_value=releases):
            self.assertEqual(7, FINALIZER.find_release(self.repo, self.tag)["id"])

    def test_missing_or_duplicate_release_is_refused(self):
        for releases in ([], [{"tag_name": self.tag}, {"tag_name": self.tag}]):
            with patch.object(FINALIZER, "api", return_value=releases):
                with self.assertRaisesRegex(ValueError, "exactly one release"):
                    FINALIZER.find_release(self.repo, self.tag)


class ReleaseCompletenessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "build.gradle.kts").write_text('version = "1.2.3"', encoding="utf-8")
        engine = self.root / "native/ringmaster-engine"
        engine.mkdir(parents=True)
        # Deliberately differs from the current Engine version: expectations must come from source.
        (engine / "Cargo.toml").write_text('[package]\nversion = "4.5.6"\n', encoding="utf-8")
        self.repo = "example/RingMASTER"
        self.tag = "v1.2.3"
        self.bundles = [f"ringmaster-engine-4.5.6-linux-{arch}.tar.gz" for arch in ("x86_64", "aarch64")]
        self.files = {platform + ".pkg": platform.encode() for platform in FINALIZER.PLATFORMS}
        self.items = [{"platform": platform, "url": self.url(platform + ".pkg"),
                       "size": len(self.files[platform + ".pkg"]),
                       "sha256": hashlib.sha256(self.files[platform + ".pkg"]).hexdigest()}
                      for platform in sorted(FINALIZER.PLATFORMS)]
        self.files.update({name: name.encode() for name in self.bundles})
        self.files["SHA256SUMS"] = "".join(
            f"{hashlib.sha256(content).hexdigest()}  {name}\n" for name, content in self.files.items()).encode()
        self.release = {"draft": True, "id": 7, "html_url": "https://example.invalid/release",
                        "assets": [{"name": name, "size": len(content),
                                    "digest": "sha256:" + hashlib.sha256(content).hexdigest()}
                                   for name, content in self.files.items()]}
        self.manifest = self.root / "ringmaster-update.json"
        self.manifest.write_text(json.dumps({"version": "1.2.3", "assets": self.items, "notes": "Changes",
                                            "notesUrl": f"https://github.com/{self.repo}/releases/tag/{self.tag}"}),
                                 encoding="utf-8")
        Path(str(self.manifest) + ".sig").write_bytes(b"offline-signature")
        self.args = SimpleNamespace(repo=self.repo, tag=self.tag, manifest=self.manifest, notes=None, publish=True)

    def url(self, name):
        return f"https://github.com/{self.repo}/releases/download/{self.tag}/{name}"

    def api(self, repo, path, **kwargs):
        self.assertFalse(kwargs, "No publication API call is permitted in these tests")
        self.assertEqual((repo, path), (self.repo, "git/ref/tags/" + self.tag))
        return {"object": {"type": "commit", "sha": "b" * 40}}

    def download(self, *args):
        if args[:3] == ("gh", "release", "download"):
            folder = Path(args[args.index("--dir") + 1])
            for name, content in self.files.items():
                (folder / name).write_bytes(content)
        else:
            self.assertEqual(args[:3], ("gh", "attestation", "verify"))
            self.assertIn("--source-digest", args)
            self.assertEqual(args[args.index("--source-digest") + 1], "b" * 40)
            self.assertEqual(args[args.index("--source-ref") + 1], "refs/tags/" + self.tag)
            self.assertEqual(args[args.index("--signer-workflow") + 1],
                             self.repo + "/.github/workflows/release.yml")
        return ""

    def exercise(self, error=None):
        with patch.object(FINALIZER, "ROOT", self.root), \
                patch.object(FINALIZER, "find_release", return_value=self.release), \
                patch.object(FINALIZER, "api", side_effect=self.api), \
                patch.object(FINALIZER, "verify_signature") as signature, \
                patch.object(FINALIZER, "command", side_effect=self.download) as command, \
                patch.object(FINALIZER.urllib.request, "build_opener") as opener:
            if error:
                with self.assertRaisesRegex(ValueError, error):
                    FINALIZER.run(self.args)
            else:
                result = FINALIZER.run(self.args)
                self.assertTrue(result["build_attestations_verified"])
            signature.assert_called_once_with(self.manifest)
            opener.assert_not_called()
            self.assertFalse(any(call.args[:3] == ("gh", "release", "upload") for call in command.call_args_list))
            return command

    def test_both_source_version_bundles_are_required(self):
        for names in ([], self.bundles[:1], self.bundles[1:]):
            with self.subTest(names=names):
                assets = copy.deepcopy(self.release["assets"])
                self.release["assets"] = [a for a in assets if a["name"] not in self.bundles or a["name"] in names]
                self.exercise("incomplete").assert_not_called()
                self.release["assets"] = assets

    def test_wrong_engine_version_is_refused(self):
        for asset in self.release["assets"]:
            asset["name"] = asset["name"].replace("engine-4.5.6-", "engine-4.5.7-")
        self.exercise("incomplete").assert_not_called()

    def test_duplicate_architecture_is_refused(self):
        self.release["assets"].append(copy.deepcopy(next(
            a for a in self.release["assets"] if a["name"] == self.bundles[0])))
        self.exercise("Duplicate").assert_not_called()

    def test_extra_engine_bundle_is_refused(self):
        self.release["assets"].append({"name": "ringmaster-engine-4.5.5-linux-aarch64.tar.gz"})
        self.exercise("Unexpected assets").assert_not_called()

    def test_missing_checksums_is_refused_before_download(self):
        self.release["assets"] = [a for a in self.release["assets"] if a["name"] != "SHA256SUMS"]
        self.exercise("incomplete").assert_not_called()

    def test_engine_checksum_mismatch_blocks_publication(self):
        self.files[self.bundles[0]] = b"changed engine"
        self.exercise("SHA256SUMS does not match")

    def test_complete_draft_checks_both_bundles_and_exact_attestations(self):
        self.args.publish = False
        command = self.exercise()
        attested = {Path(call.args[3]).name for call in command.call_args_list
                    if call.args[:3] == ("gh", "attestation", "verify")}
        self.assertEqual(attested, set(self.files))

    def test_existing_different_feed_is_never_overwritten(self):
        self.files["ringmaster-update.json"] = b"different bytes"
        self.exercise("will not be overwritten")


if __name__ == "__main__": unittest.main()
