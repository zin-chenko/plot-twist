"""Manifest integrity tests only; these bytes are never used as an inference model."""

import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from download_model import MODEL, FILES, ALGORITHM, digest, validate, main


class ModelIntegrityTest(unittest.TestCase):
    def test_corruption_and_fingerprint_changes_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in FILES:
                path = directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"integrity-test-only")
            manifest = dict(
                repository=MODEL,
                revision="a" * 40,
                algorithm=ALGORITHM,
                files={name: digest(directory / name) for name in FILES},
            )
            manifest["model_key"] = hashlib.sha256(
                json.dumps(manifest, sort_keys=True).encode()
            ).hexdigest()
            target = directory / "manifest.json"
            target.write_text(json.dumps(manifest))
            self.assertEqual(validate(directory), manifest)
            (directory / FILES[0]).write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "checksum"):
                validate(directory)
            (directory / FILES[0]).write_bytes(b"integrity-test-only")
            manifest["model_key"] = "0" * 64
            target.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "fingerprint"):
                validate(directory)

    def test_production_refuses_unpinned_download_before_network_access(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            os.environ,
            MODEL_DIRECTORY=directory,
            MODEL_REQUIRE_PIN="true",
            MODEL_REVISION="main",
        ), patch("download_model.urlopen") as network:
            with self.assertRaisesRegex(SystemExit, "pinned"):
                main()
            network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
