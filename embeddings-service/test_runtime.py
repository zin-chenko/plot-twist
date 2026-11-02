"""Negative test using real ONNX Runtime: invalid fixture bytes can never pass warmup."""

import tempfile
from pathlib import Path
import unittest
from tokenizers import Tokenizer
from tokenizers.models import WordLevel
from download_model import verify_model


class RuntimeValidationTest(unittest.TestCase):
    def test_invalid_protobuf_is_rejected_before_manifest_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "onnx").mkdir()
            (directory / "onnx/model_quantized.onnx").write_bytes(
                b"invalid ONNX fixture"
            )
            tokenizer = Tokenizer(
                WordLevel(
                    {"<unk>": 0, "<pad>": 1, "<s>": 2, "</s>": 3}, unk_token="<unk>"
                )
            )
            tokenizer.save(str(directory / "tokenizer.json"))
            with self.assertRaisesRegex(ValueError, "InvalidProtobuf"):
                verify_model(directory, {"model_key": "a" * 64})
            self.assertFalse((directory / "manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
