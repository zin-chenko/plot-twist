# syntax=docker/dockerfile:1
FROM python:3.12-slim-bookworm@sha256:e697c81897302a4c4333bade000d98038fbb21eb064f8ee1fd42d02ebcd9d337
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TOKENIZERS_PARALLELISM=false HF_HUB_OFFLINE=1
WORKDIR /app
COPY requirements.lock ./
RUN --mount=type=secret,id=pip_config,target=/run/secrets/pip_config \
    --mount=type=secret,id=ca_certs,target=/run/secrets/ca_certs \
    if [ -f /run/secrets/pip_config ]; then export PIP_CONFIG_FILE=/run/secrets/pip_config; fi; \
    if [ -f /run/secrets/ca_certs ]; then export PIP_CERT=/run/secrets/ca_certs; fi; \
    pip install --no-cache-dir --only-binary=:all: --require-hashes -r requirements.lock
COPY app.py download_model.py ./
USER 10001:10001
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=12 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/healthz', timeout=2)"]
CMD ["uvicorn", "app:application", "--host", "0.0.0.0", "--port", "8090", "--workers", "1", "--no-access-log", "--timeout-keep-alive", "5"]
