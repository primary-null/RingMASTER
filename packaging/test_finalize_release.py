"""Offline regressions for the distribution publication gate."""
import copy
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import re
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


if __name__ == "__main__": unittest.main()
