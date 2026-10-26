"""Loopback-only image OCR service. No image URLs, uploads on disk, or cloud calls."""
from contextlib import asynccontextmanager
from io import BytesIO
from pathlib import Path
from threading import Lock
import math
import uuid

import numpy as np
from fastapi import FastAPI, HTTPException, Request
from PIL import Image, ImageDraw, ImageFont, UnidentifiedImageError
import rapidocr
from rapidocr import RapidOCR
from starlette.concurrency import run_in_threadpool

MAX_BYTES = 5 * 1024 * 1024
MAX_PIXELS = 20_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
model = None
inference_lock = Lock()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global model
    model_root = Path(rapidocr.__file__).resolve().parent / "models"
    paths = {"Det.model_path": model_root / "PP-OCRv6_det_small.onnx",
             "Cls.model_path": model_root / "ch_ppocr_mobile_v2.0_cls_mobile.onnx",
             "Rec.model_path": model_root / "PP-OCRv6_rec_small.onnx"}
    if not all(path.is_file() for path in paths.values()):
        raise RuntimeError("LOCAL_MODEL_FILE_MISSING")
    params = {key: str(path) for key, path in paths.items()}
    params.update({"EngineConfig.onnxruntime.intra_op_num_threads": 2,
                   "EngineConfig.onnxruntime.inter_op_num_threads": 1})
    prepared = RapidOCR(params=params)
    warmup = Image.new("RGB", (650, 200), "white")
    ImageDraw.Draw(warmup).text((30, 60), "LOCAL OCR READY", fill="black",
                               font=ImageFont.load_default(size=48))
    recognized = prepared(np.asarray(warmup)[:, :, ::-1].copy())
    if recognized.txts is None or not len(recognized.txts):
        raise RuntimeError("LOCAL_MODEL_WARMUP_FAILED")
    model = prepared
    yield
    model = None


app = FastAPI(title="RepairSystem local OCR", docs_url=None, redoc_url=None,
              openapi_url=None, lifespan=lifespan)


@app.get("/health")
def health():
    if model is None:
        raise HTTPException(503, "MODEL_NOT_READY")
    return {"status": "UP", "provider": "LOCAL_RAPIDOCR", "device": "CPU"}


def recognize_image(content: bytes):
    try:
        with Image.open(BytesIO(content)) as image:
            if image.format not in ("PNG", "JPEG"):
                raise HTTPException(415, "UNSUPPORTED_IMAGE")
            if image.width * image.height > MAX_PIXELS:
                raise HTTPException(413, "IMAGE_DIMENSIONS_EXCEEDED")
            image.load()
            array = np.asarray(image.convert("RGB"))[:, :, ::-1].copy()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise HTTPException(400, "INVALID_IMAGE") from None
    if not inference_lock.acquire(blocking=False):
        raise HTTPException(429, "MODEL_BUSY")
    try:
        result = model(array)
        lines = []
        if result.txts is not None:
            if len(result.txts) > 2000:
                raise HTTPException(422, "TEXT_LIMIT_EXCEEDED")
            for text, score, box in zip(result.txts, result.scores, result.boxes):
                if len(text) > 2000:
                    raise HTTPException(422, "TEXT_LIMIT_EXCEEDED")
                confidence = float(score)
                if not math.isfinite(confidence) or not 0 <= confidence <= 1:
                    confidence = None
                lines.append({"text": text, "confidence": confidence,
                              "polygon": [{"x": int(round(float(point[0]))),
                                           "y": int(round(float(point[1])))} for point in box[:4]]})
        return {"lines": lines, "requestId": "local-" + uuid.uuid4().hex,
                "confidenceUnit": "local-model:0..1"}
    finally:
        inference_lock.release()


@app.post("/v1/recognize")
async def recognize(request: Request):
    if model is None:
        raise HTTPException(503, "MODEL_NOT_READY")
    if request.headers.get("content-type", "").split(";")[0] not in (
            "application/octet-stream", "image/png", "image/jpeg"):
        raise HTTPException(415, "UNSUPPORTED_CONTENT_TYPE")
    content = bytearray()
    async for chunk in request.stream():
        content.extend(chunk)
        if len(content) > MAX_BYTES:
            raise HTTPException(413, "IMAGE_SIZE_EXCEEDED")
    if not content:
        raise HTTPException(400, "EMPTY_IMAGE")
    try:
        return await run_in_threadpool(recognize_image, bytes(content))
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, "INFERENCE_FAILED") from None
