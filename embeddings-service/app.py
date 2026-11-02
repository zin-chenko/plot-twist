"""CPU-only multilingual E5 inference. Story content is never logged or sent to a provider."""

from contextlib import asynccontextmanager
import logging
import os
from pathlib import Path
import threading

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
import numpy as np
import onnxruntime as ort
from pydantic import BaseModel, ConfigDict, Field
from tokenizers import Tokenizer

from download_model import validate

DIMENSIONS = 384
MAX_TOKENS = 512
OVERLAP = 48
BATCH = 4
MAX_CHUNKS = 64
LIMIT = threading.BoundedSemaphore(2)
log = logging.getLogger("plot_twist.embeddings")


def normalize(vector):
    norm = float(np.linalg.norm(vector))
    if not np.isfinite(norm) or norm < 1e-12:
        raise ValueError("Invalid embedding")
    return vector / norm


class Encoder:
    def __init__(self, directory, manifest=None):
        # The installer supplies its verified candidate before publishing manifest.json.
        self.manifest = validate(directory) if manifest is None else manifest
        self.tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
        self.tokenizer.no_truncation()
        self.tokenizer.no_padding()
        self.pad = self.tokenizer.token_to_id("<pad>")
        self.begin = self.tokenizer.token_to_id("<s>")
        self.end = self.tokenizer.token_to_id("</s>")
        if None in (self.pad, self.begin, self.end):
            raise ValueError("Unexpected E5 tokenizer")
        self.prefix = self.tokenizer.encode("passage: ", add_special_tokens=False).ids
        options = ort.SessionOptions()
        options.intra_op_num_threads = int(os.getenv("INFERENCE_THREADS", "2"))
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.session = ort.InferenceSession(
            str(directory / "onnx/model_quantized.onnx"),
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )
        self.inputs = {value.name for value in self.session.get_inputs()}
        if not {"input_ids", "attention_mask"} <= self.inputs:
            raise ValueError("Unexpected ONNX inputs")

    def encode(self, text):
        tokens = self.tokenizer.encode(text, add_special_tokens=False).ids
        window = MAX_TOKENS - len(self.prefix) - 2
        pieces = [
            tokens[start : start + window]
            for start in range(0, len(tokens), window - OVERLAP)
        ]
        if len(pieces) > MAX_CHUNKS:
            raise ValueError("Document token budget exceeded")
        if not pieces:
            raise ValueError("Empty document")
        vectors, weights = [], []
        for offset in range(0, len(pieces), BATCH):
            content = pieces[offset : offset + BATCH]
            sequences = [
                [self.begin] + self.prefix + piece + [self.end] for piece in content
            ]
            width = max(map(len, sequences))
            ids = np.full((len(sequences), width), self.pad, dtype=np.int64)
            mask = np.zeros_like(ids)
            for index, sequence in enumerate(sequences):
                ids[index, : len(sequence)] = sequence
                mask[index, : len(sequence)] = 1
            values = {
                "input_ids": ids,
                "attention_mask": mask,
                "token_type_ids": np.zeros_like(ids),
            }
            hidden = self.session.run(
                None, {name: values[name] for name in self.inputs}
            )[0]
            if hidden.ndim != 3 or hidden.shape[2] != DIMENSIONS:
                raise ValueError("Unexpected model output")
            pooled = (hidden * mask[..., None]).sum(axis=1) / mask.sum(axis=1)[:, None]
            vectors.extend(normalize(vector) for vector in pooled)
            weights.extend(
                max(1, len(piece) - (OVERLAP if offset + index else 0))
                for index, piece in enumerate(content)
            )
        result = normalize(np.average(np.asarray(vectors), axis=0, weights=weights))
        if result.shape != (DIMENSIONS,) or not np.isfinite(result).all():
            raise ValueError("Invalid output shape")
        return result.astype(np.float32).tolist()


@asynccontextmanager
async def lifespan(app):
    app.state.encoder = Encoder(Path(os.getenv("MODEL_DIRECTORY", "/model")))
    # Readiness includes an actual inference, not just opening a socket.
    app.state.encoder.encode("История начинается с маленького открытия.")
    log.info("Embedding model ready: %s", app.state.encoder.manifest["model_key"])
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=20500)
    model_key: str = Field(pattern="^[0-9a-f]{64}$")


@app.get("/healthz")
def health():
    encoder = app.state.encoder
    return {
        "ready": True,
        "dimensions": DIMENSIONS,
        "model_key": encoder.manifest["model_key"],
        "revision": encoder.manifest["revision"],
    }


@app.post("/embed")
def embed(body: Input):
    encoder = app.state.encoder
    if body.model_key != encoder.manifest["model_key"]:
        raise HTTPException(409, "Model version changed")
    if not LIMIT.acquire(blocking=False):
        raise HTTPException(429, "Inference busy", headers={"Retry-After": "1"})
    try:
        if not body.text.strip() or "\x00" in body.text:
            raise HTTPException(400, "Invalid text")
        return {"model_key": body.model_key, "vector": encoder.encode(body.text)}
    except ValueError:
        raise HTTPException(400, "Input exceeds model limits") from None
    finally:
        LIMIT.release()


# Bound raw request bytes, including chunked JSON; do not log rejected document contents.
class BodyLimit:
    def __init__(self, application):
        self.application = application

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] != "POST":
            await self.application(scope, receive, send)
            return
        payload = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            payload.extend(message.get("body", b""))
            if len(payload) > 256 * 1024:
                await JSONResponse({"detail": "Request too large"}, 413)(
                    scope, receive, send
                )
                return
            if not message.get("more_body"):
                break
        sent = False

        async def replay():
            nonlocal sent
            if sent:
                return {"type": "http.disconnect"}
            sent = True
            return {"type": "http.request", "body": bytes(payload), "more_body": False}

        await self.application(scope, replay, send)


application = BodyLimit(app)
