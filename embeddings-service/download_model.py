"""Fetch public ONNX weights once, bind every file to one HF commit and record checksums."""

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.request import Request, urlopen

MODEL = "Xenova/multilingual-e5-small"
FILES = ("onnx/model_quantized.onnx", "tokenizer.json")
ALGORITHM = "e5-passage-q8-chunks512-overlap48-mean-l2-v1"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def validate(directory):
    manifest = json.loads((directory / "manifest.json").read_text())
    if manifest["repository"] != MODEL or manifest["algorithm"] != ALGORITHM:
        raise ValueError("Unexpected model or pooling algorithm")
    if not re.fullmatch("[a-f0-9]{40}", manifest["revision"]):
        raise ValueError("Invalid model revision")
    for name in FILES:
        if digest(directory / name) != manifest["files"][name]:
            raise ValueError("Model checksum mismatch")
    identity = {
        key: manifest[key] for key in ("repository", "revision", "algorithm", "files")
    }
    if (
        hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        != manifest["model_key"]
    ):
        raise ValueError("Model fingerprint mismatch")
    return manifest


def main():
    directory = Path(os.getenv("MODEL_DIRECTORY", "/model"))
    directory.mkdir(parents=True, exist_ok=True)
    requested = os.getenv("MODEL_REVISION", "main")
    if os.getenv("MODEL_REQUIRE_PIN") == "true" and not re.fullmatch(
        "[a-f0-9]{40}", requested
    ):
        raise SystemExit(
            "Production requires MODEL_REVISION pinned to a full verified model commit SHA"
        )
    if (directory / "manifest.json").exists():
        manifest = validate(directory)
        if requested != "main" and requested != manifest["revision"]:
            raise SystemExit(
                "Model volume belongs to another revision; use a new volume for rollout"
            )
        print("Verified cached embedding model:", manifest["model_key"])
        return
    if requested != "main" and not re.fullmatch("[a-f0-9]{40}", requested):
        raise SystemExit("MODEL_REVISION must be a full Hugging Face commit SHA")
    with urlopen(
        f"https://huggingface.co/api/models/{MODEL}/revision/{requested}", timeout=30
    ) as response:
        revision = json.load(response)["sha"]
    if not re.fullmatch("[a-f0-9]{40}", revision):
        raise SystemExit("Invalid upstream model revision")
    if requested != "main" and revision != requested:
        raise SystemExit("Upstream returned another revision")
    hashes = {}
    for name in FILES:
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        # A partial file is never activated as a model.
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as temporary:
            partial = Path(temporary.name)
            try:
                request = Request(
                    f"https://huggingface.co/{MODEL}/resolve/{revision}/{name}",
                    headers={"User-Agent": "plot-twist-model-init/1"},
                )
                with urlopen(request, timeout=180) as response:
                    while chunk := response.read(1024 * 1024):
                        temporary.write(chunk)
                temporary.flush()
                os.fsync(temporary.fileno())
            except BaseException:
                partial.unlink(missing_ok=True)
                raise
        partial.chmod(0o644)
        partial.replace(target)
        hashes[name] = digest(target)
    manifest = {
        "repository": MODEL,
        "revision": revision,
        "algorithm": ALGORITHM,
        "files": hashes,
    }
    manifest["model_key"] = hashlib.sha256(
        json.dumps(manifest, sort_keys=True).encode()
    ).hexdigest()
    pending = directory / "manifest.pending"
    pending.write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    pending.chmod(0o644)
    pending.replace(directory / "manifest.json")
    validate(directory)
    print("Downloaded and verified embedding model revision:", revision)
    print("Model identity:", manifest["model_key"])


if __name__ == "__main__":
    main()
