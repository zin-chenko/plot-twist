"""Manifest integrity tests only; these bytes are never used as an inference model."""

import hashlib
import json
import os
import io
from contextlib import redirect_stdout
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from download_model import (
    MODEL,
    FILES,
    ALGORITHM,
    digest,
    validate,
    main,
    source_files,
    verify_source_file,
)


class ModelIntegrityTest(unittest.TestCase):
    def test_upstream_metadata_requires_weight_sha_and_checks_git_tokenizer_blob(self):
        token = b'{"fixture":true}'
        info = {
            "siblings": [
                {"rfilename": FILES[0], "size": 100, "lfs": {"sha256": "a" * 64}},
                {
                    "rfilename": FILES[1],
                    "size": len(token),
                    "blobId": hashlib.sha1(
                        f"blob {len(token)}\0".encode() + token
                    ).hexdigest(),
                },
            ]
        }
        sources = source_files(info)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.json"
            path.write_bytes(token)
            verify_source_file(path, sources[FILES[1]])
            path.write_bytes(token.replace(b"true", b"null"))
            with self.assertRaisesRegex(OSError, "checksum"):
                verify_source_file(path, sources[FILES[1]])
        del info["siblings"][0]["lfs"]
        with self.assertRaisesRegex(ValueError, "upstream checksum"):
            source_files(info)

    def installation_fixture(self, directory):
        hashes = {}
        info = {"sha": "a" * 40, "siblings": []}
        for name in FILES:
            target = directory / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"integrity-test-only")
            hashes[name] = digest(target)
            info["siblings"].append(
                {
                    "rfilename": name,
                    "size": target.stat().st_size,
                    "lfs": {"sha256": hashes[name]},
                }
            )
        manifest = dict(
            repository=MODEL, revision=info["sha"], algorithm=ALGORITHM, files=hashes
        )
        manifest["model_key"] = hashlib.sha256(
            json.dumps(manifest, sort_keys=True).encode()
        ).hexdigest()
        return info, manifest

    def test_legacy_cache_is_verified_at_original_commit_without_redownloading_valid_files(
        self,
    ):
        with tempfile.TemporaryDirectory() as temporary, redirect_stdout(
            io.StringIO()
        ), patch.dict(
            os.environ,
            MODEL_DIRECTORY=temporary,
            MODEL_REVISION="main",
            MODEL_REQUIRE_PIN="false",
        ):
            directory = Path(temporary)
            info, manifest = self.installation_fixture(directory)
            (directory / "manifest.json").write_text(json.dumps(manifest))
            with patch(
                "download_model.urlopen",
                return_value=io.BytesIO(json.dumps(info).encode()),
            ) as network, patch("download_model.verify_model") as warmup, patch(
                "download_model.download_file"
            ) as transfer:
                main()
                transfer.assert_not_called()
                warmup.assert_called_once()
                self.assertIn(
                    "/revision/" + info["sha"] + "?blobs=true",
                    network.call_args.args[0],
                )
            self.assertEqual(validate(directory)["model_key"], manifest["model_key"])
            self.assertEqual(validate(directory)["verification_version"], 1)

    def test_candidate_is_not_published_when_inference_validation_fails(self):
        with tempfile.TemporaryDirectory() as temporary, redirect_stdout(
            io.StringIO()
        ), patch.dict(
            os.environ,
            MODEL_DIRECTORY=temporary,
            MODEL_REVISION="main",
            MODEL_REQUIRE_PIN="false",
        ):
            directory = Path(temporary)
            info, _ = self.installation_fixture(directory)
            with patch(
                "download_model.urlopen",
                return_value=io.BytesIO(json.dumps(info).encode()),
            ), patch(
                "download_model.verify_model", side_effect=ValueError("invalid ONNX")
            ):
                with self.assertRaisesRegex(ValueError, "invalid ONNX"):
                    main()
            self.assertFalse((directory / "manifest.json").exists())

    def test_self_consistent_but_corrupt_legacy_cache_is_repaired_from_upstream(self):
        with tempfile.TemporaryDirectory() as temporary, redirect_stdout(
            io.StringIO()
        ), patch.dict(
            os.environ,
            MODEL_DIRECTORY=temporary,
            MODEL_REVISION="main",
            MODEL_REQUIRE_PIN="false",
        ):
            directory = Path(temporary)
            info, manifest = self.installation_fixture(directory)
            target = directory / FILES[0]
            target.write_bytes(b"bad")
            manifest["files"][FILES[0]] = digest(target)
            manifest.pop("model_key")
            manifest["model_key"] = hashlib.sha256(
                json.dumps(manifest, sort_keys=True).encode()
            ).hexdigest()
            (directory / "manifest.json").write_text(json.dumps(manifest))
            validate(directory)  # This reproduces the old false success.

            def repair(url, path, deadline, expected):
                path.write_bytes(b"integrity-test-only")
                verify_source_file(path, expected)
                return digest(path)

            with patch(
                "download_model.urlopen",
                return_value=io.BytesIO(json.dumps(info).encode()),
            ), patch("download_model.verify_model"), patch(
                "download_model.download_file", side_effect=repair
            ) as transfer:
                main()
                transfer.assert_called_once()
            self.assertEqual(
                validate(directory)["files"][FILES[0]],
                info["siblings"][0]["lfs"]["sha256"],
            )

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
