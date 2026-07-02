import json
import os
import numpy as np
import onnxruntime as ort
from http.server import HTTPServer, BaseHTTPRequestHandler
from tokenizers import Tokenizer

MODEL_DIR = os.environ.get("MODEL_DIR", "/opt/model")
PORT = int(os.environ.get("EMBEDDING_PORT", "9222"))

print(f"Loading tokenizer from {MODEL_DIR}...", flush=True)
tokenizer = Tokenizer.from_file(os.path.join(MODEL_DIR, "tokenizer.json"))
tokenizer.enable_padding(length=512)
tokenizer.enable_truncation(max_length=512)

print(f"Loading ONNX model from {MODEL_DIR}...", flush=True)
session = ort.InferenceSession(
    os.path.join(MODEL_DIR, "model.onnx"),
    providers=["CPUExecutionProvider"]
)
print(f"Model loaded (ready to serve on port {PORT})", flush=True)


class EmbedHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/embed":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length))
            text = body.get("text", "")
            if not text:
                self._send(400, {"error": "text is required"})
                return

            encoded = tokenizer.encode(text)
            input_ids = np.array([encoded.ids], dtype=np.int64)
            attention_mask = np.array([encoded.attention_mask], dtype=np.int64)
            token_type_ids = np.zeros_like(input_ids)

            outputs = session.run(None, {
                "input_ids": input_ids,
                "attention_mask": attention_mask,
                "token_type_ids": token_type_ids,
            })

            cls_embedding = outputs[0][0][0].astype(float)
            norm = np.linalg.norm(cls_embedding)
            if norm > 0:
                cls_embedding = cls_embedding / norm

            self._send(200, {"embedding": cls_embedding.tolist()})
        except Exception as e:
            self._send(500, {"error": str(e)})

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ready"})
        else:
            self._send(404, {"error": "not found"})

    def _send(self, code, obj):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(obj).encode())

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), EmbedHandler)
    print(f"Embedding server listening on port {PORT}", flush=True)
    server.serve_forever()
