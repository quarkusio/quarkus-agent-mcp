FROM pgvector/pgvector:pg17 AS base

# --- Stage 1: export ONNX model (needs PyTorch, discarded after) ---
FROM python:3.12-slim AS model-builder

RUN pip install --no-cache-dir optimum[onnxruntime] tokenizers

RUN python -c "\
from optimum.onnxruntime import ORTModelForFeatureExtraction; \
from tokenizers import Tokenizer; \
model = ORTModelForFeatureExtraction.from_pretrained('BAAI/bge-small-en-v1.5', export=True); \
model.save_pretrained('/opt/model'); \
t = Tokenizer.from_pretrained('BAAI/bge-small-en-v1.5'); \
t.save('/opt/model/tokenizer.json'); \
print('Model exported')"

# --- Stage 2: final image (no PyTorch) ---
FROM base

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*

RUN pip3 install --no-cache-dir --break-system-packages \
    onnxruntime numpy tokenizers

COPY --from=model-builder /opt/model /opt/model
COPY embed_server.py /opt/embed_server.py
COPY start.sh /opt/start.sh
RUN chmod +x /opt/start.sh

EXPOSE 5432 9222

ENTRYPOINT ["/opt/start.sh"]
