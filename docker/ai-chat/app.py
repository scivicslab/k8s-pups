"""AI Chat: a chat screen for the models of an OpenAI-compatible server (the GPU broker).

It is served under the session path of k8s-pups, so the path prefix (PUPS_SESSION_PATH) is given to Gradio as root_path.
Each exchange is appended to a transcript file under ~/works/chat-history, in the user's own storage.
"""
import json
import os
import time
from pathlib import Path

import gradio as gr
from openai import OpenAI

BASE_URL = os.environ.get("AI_CHAT_BASE_URL", "http://gpu-broker:28005/v1")
DEFAULT_MODEL = os.environ.get("AI_CHAT_DEFAULT_MODEL", "")
HISTORY_DIR = Path(os.environ.get("AI_CHAT_HISTORY_DIR", str(Path.home() / "works" / "chat-history")))

client = OpenAI(base_url=BASE_URL, api_key="not-needed", timeout=600)


def list_models():
    try:
        return [m.id for m in client.models.list().data]
    except Exception:
        return [DEFAULT_MODEL] if DEFAULT_MODEL else []


def save(model, system, user, answer):
    try:
        HISTORY_DIR.mkdir(parents=True, exist_ok=True)
        day = time.strftime("%Y-%m-%d")
        with open(HISTORY_DIR / f"{day}.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps({"time": time.strftime("%Y-%m-%dT%H:%M:%S"), "model": model, "system": system,
                                "user": user, "assistant": answer}, ensure_ascii=False) + "\n")
    except OSError:
        pass  # a full or read-only storage must not stop the conversation


def respond(message, history, model, system):
    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    for turn in history:
        messages.append({"role": turn["role"], "content": turn["content"]})
    messages.append({"role": "user", "content": message})
    answer = ""
    stream = client.chat.completions.create(model=model, messages=messages, stream=True)
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta.content:
            answer += chunk.choices[0].delta.content
            yield answer
    save(model, system, message, answer)


models = list_models()
demo = gr.ChatInterface(
    respond,
    title="AI Chat",
    additional_inputs=[
        gr.Dropdown(choices=models, value=DEFAULT_MODEL if DEFAULT_MODEL in models else (models[0] if models else None),
                    label="Model", allow_custom_value=True),
        gr.Textbox(label="System prompt", lines=2),
    ],
)

if __name__ == "__main__":
    demo.queue().launch(server_name="0.0.0.0", server_port=8080,
                        root_path=os.environ.get("PUPS_SESSION_PATH", "").rstrip("/"))
