"""Fetch public ONNX weights once, bind every file to one HF commit and record checksums."""

import hashlib
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

MODEL = "Xenova/multilingual-e5-small"
FILES = ("onnx/model_quantized.onnx", "tokenizer.json")
ALGORITHM = "e5-passage-q8-chunks512-overlap48-mean-l2-v1"
DOWNLOAD_ATTEMPTS = 3
NETWORK_TIMEOUT = 30
INSTALL_TIMEOUT = 1800


def report(message):
    print(message, flush=True)


def failure_detail(error):
    # Redirects may include signed URLs: never log a response URL or request headers.
    if isinstance(error, HTTPError):
        return f"HTTP {error.code}"
    return f"{type(error).__name__}: {error}"


def download_file(url, target, deadline, expected=None):
    """Bound the transfer, report progress, and activate only a complete file."""
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        if time.monotonic() >= deadline:
            raise TimeoutError("Model installation exceeded 30 minutes")
        report(f"Downloading {target.name}: attempt {attempt}/{DOWNLOAD_ATTEMPTS}")
        partial = None
        try:
            with tempfile.NamedTemporaryFile(
                dir=target.parent, prefix=".download-", suffix=".part", delete=False
            ) as temporary:
                partial = Path(temporary.name)
                request = Request(
                    url, headers={"User-Agent": "plot-twist-model-init/1"}
                )
                with urlopen(
                    request,
                    timeout=min(NETWORK_TIMEOUT, max(1, deadline - time.monotonic())),
                ) as response:
                    length = response.headers.get("Content-Length")
                    total = int(length) if length else None
                    if (
                        expected is not None
                        and total is not None
                        and total != expected["size"]
                    ):
                        raise OSError(
                            f"Upstream size mismatch: expected {expected['size']} bytes, HTTP advertised {total}"
                        )
                    received = 0
                    started = last_report = time.monotonic()
                    if total is not None:
                        report(f"{target.name}: expected {total / 1048576:.1f} MiB")
                    while True:
                        if time.monotonic() >= deadline:
                            raise TimeoutError("Model installation exceeded 30 minutes")
                        # read1 returns available bytes: a trickling server cannot hide the deadline.
                        chunk = response.read1(64 * 1024)
                        if not chunk:
                            break
                        temporary.write(chunk)
                        received += len(chunk)
                        now = time.monotonic()
                        if now - last_report >= 10:
                            progress = f"{received / 1048576:.1f} MiB"
                            if total:
                                progress += f" / {total / 1048576:.1f} MiB ({100 * received / total:.1f}%)"
                            report(
                                f"{target.name}: {progress}, elapsed {now - started:.0f}s"
                            )
                            last_report = now
                    if total is not None and received != total:
                        raise OSError(
                            f"Incomplete download: {received} of {total} bytes"
                        )
                    if received == 0:
                        raise OSError("Downloaded file is empty")
                temporary.flush()
                os.fsync(temporary.fileno())
            if expected is not None:
                verify_source_file(partial, expected)
            partial.chmod(0o644)
            partial.replace(target)
            report(
                f"Downloaded {target.name}: {received / 1048576:.1f} MiB; calculating SHA-256"
            )
            return digest(target)
        except (OSError, HTTPException) as error:
            if partial is not None:
                partial.unlink(missing_ok=True)
            report(f"Download failed for {target.name}: {failure_detail(error)}")
            if (
                attempt == DOWNLOAD_ATTEMPTS
                or time.monotonic() >= deadline
                or isinstance(error, HTTPError)
                and error.code in (400, 401, 403, 404)
            ):
                raise
            time.sleep(min(2**attempt, max(0, deadline - time.monotonic())))


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def source_files(info):
    """Use commit-bound HF blob metadata, not a hash invented after downloading."""
    siblings = {entry["rfilename"]: entry for entry in info.get("siblings", [])}
    result = {}
    for name in FILES:
        entry = siblings.get(name, {})
        size = entry.get("size")
        lfs = entry.get("lfs") or {}
        sha = lfs.get("sha256")
        blob = entry.get("blobId")
        if not isinstance(size, int) or size <= 0:
            raise ValueError(
                f"Missing upstream size for {name}; blobs=true metadata is required"
            )
        if sha and re.fullmatch("[a-f0-9]{64}", sha):
            result[name] = {"size": size, "sha256": sha}
        elif (
            name != FILES[0]
            and isinstance(blob, str)
            and re.fullmatch("[a-f0-9]{40}", blob)
        ):
            result[name] = {"size": size, "git_blob_sha1": blob}
        else:
            raise ValueError(f"Missing upstream checksum for {name}")
    return result


def verify_source_file(path, expected):
    size = path.stat().st_size
    if size != expected["size"]:
        raise OSError(
            f"Upstream size mismatch: expected {expected['size']} bytes, received {size}"
        )
    if "sha256" in expected:
        matched = digest(path) == expected["sha256"]
    else:
        value = hashlib.sha1(f"blob {size}\0".encode())
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                value.update(block)
        matched = value.hexdigest() == expected["git_blob_sha1"]
    if not matched:
        raise OSError("Downloaded content does not match the upstream checksum")


def verify_model(directory, manifest):
    """Validate with the actual application encoder before declaring installation complete."""
    report("Validating ONNX/tokenizer and running a real embedding warmup")
    try:
        from app import Encoder

        Encoder(directory, manifest=manifest).encode(
            "История начинается с маленького открытия."
        )
    except Exception as error:
        raise ValueError(
            f"Model warmup failed ({type(error).__name__}): {error}"
        ) from None
    report("ONNX embedding warmup passed")


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
        report("Checking cached embedding model checksums")
        manifest = json.loads((directory / "manifest.json").read_text())
        if manifest.get("repository") != MODEL or not re.fullmatch(
            "[a-f0-9]{40}", manifest.get("revision", "")
        ):
            raise ValueError(
                "Unexpected cached model identity; use a separate model volume"
            )
        if requested != "main" and requested != manifest["revision"]:
            raise SystemExit(
                "Model volume belongs to another revision; use a new volume for rollout"
            )
        # Preserve the original commit during repair; never silently upgrade a cached main.
        requested = manifest["revision"]
        try:
            manifest = validate(directory)
            if manifest.get("verification_version") == 1:
                verify_model(directory, manifest)
                report(f"Verified cached embedding model: {manifest['model_key']}")
                return
            report("Legacy cache requires upstream checksum verification")
        except (ValueError, OSError) as error:
            report(f"Cached model needs repair: {failure_detail(error)}")
    if requested != "main" and not re.fullmatch("[a-f0-9]{40}", requested):
        raise SystemExit("MODEL_REVISION must be a full Hugging Face commit SHA")
    deadline = time.monotonic() + INSTALL_TIMEOUT
    report(f"Resolving embedding model {MODEL}, revision {requested}")
    with urlopen(
        f"https://huggingface.co/api/models/{MODEL}/revision/{requested}?blobs=true",
        timeout=NETWORK_TIMEOUT,
    ) as response:
        info = json.load(response)
        revision = info["sha"]
    if not re.fullmatch("[a-f0-9]{40}", revision):
        raise SystemExit("Invalid upstream model revision")
    if requested != "main" and revision != requested:
        raise SystemExit("Upstream returned another revision")
    report(f"Resolved embedding model commit: {revision}")
    sources = source_files(info)
    hashes = {}
    for name in FILES:
        target = directory / name
        try:
            verify_source_file(target, sources[name])
            report(f"Reusing {name}: upstream size and checksum match")
            hashes[name] = digest(target)
        except OSError:
            hashes[name] = download_file(
                f"https://huggingface.co/{MODEL}/resolve/{revision}/{name}",
                target,
                deadline,
                expected=sources[name],
            )
    manifest = {
        "repository": MODEL,
        "revision": revision,
        "algorithm": ALGORITHM,
        "files": hashes,
    }
    manifest["model_key"] = hashlib.sha256(
        json.dumps(manifest, sort_keys=True).encode()
    ).hexdigest()
    manifest["verification_version"] = 1
    manifest["source_files"] = sources
    verify_model(directory, manifest)
    pending = directory / "manifest.pending"
    pending.write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    pending.chmod(0o644)
    pending.replace(directory / "manifest.json")
    validate(directory)
    print("Downloaded and verified embedding model revision:", revision)
    print("Model identity:", manifest["model_key"])


if __name__ == "__main__":
    try:
        main()
    except (OSError, HTTPException, ValueError) as error:
        print(
            f"Model installation failed: {failure_detail(error)}",
            file=sys.stderr,
            flush=True,
        )
        raise SystemExit(1) from None
