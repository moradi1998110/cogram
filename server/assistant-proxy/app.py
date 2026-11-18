"""Phogram's assistant proxy.

The app never holds the AI service's key. It sends a conversation here with the user's own app token; this
service checks the token and the user's daily quota, asks Claude for the reply with the organization's key, and
streams the reply back as server-sent events. When the app closes the connection (the user pressed stop), the
request to Claude is closed too, so nothing more is generated or billed.

Events, one JSON object per `data:` line:
  {"type": "delta", "text": "..."}         a piece of the reply
  {"type": "done", "stop_reason": "..."}    the reply is complete
  {"type": "error", "code": "...", "message": "..."}
"""

import json
import logging
import os
import sqlite3
import threading
import time
from pathlib import Path

import anthropic
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

log = logging.getLogger("assistant-proxy")

MODEL = os.environ.get("ASSISTANT_MODEL", "claude-opus-5-5")
EFFORT = os.environ.get("ASSISTANT_EFFORT", "medium")
MAX_TOKENS = int(os.environ.get("ASSISTANT_MAX_TOKENS", "64000"))
DAILY_REQUESTS = int(os.environ.get("ASSISTANT_DAILY_REQUESTS", "200"))
MAX_HISTORY_MESSAGES = int(os.environ.get("ASSISTANT_MAX_HISTORY", "60"))
TOKENS_FILE = Path(os.environ.get("ASSISTANT_TOKENS_FILE", "tokens.json"))
USAGE_DB = Path(os.environ.get("ASSISTANT_USAGE_DB", "usage.sqlite3"))
SYSTEM_PROMPT_FILE = Path(os.environ.get("ASSISTANT_SYSTEM_PROMPT_FILE", "system_prompt.txt"))

DEFAULT_SYSTEM_PROMPT = (
    "You are the assistant inside Phogram, a messaging app used by a small organization. "
    "Answer in the language the user writes in; most users write Arabic. "
    "Keep answers clear and well organized, and use Markdown only for lists, bold text and code."
)

# The key comes from ANTHROPIC_API_KEY in this service's environment, never from the app.
client = anthropic.AsyncAnthropic()
app = FastAPI(title="Phogram assistant proxy")


def load_system_prompt() -> str:
    if SYSTEM_PROMPT_FILE.exists():
        return SYSTEM_PROMPT_FILE.read_text(encoding="utf-8").strip()
    return DEFAULT_SYSTEM_PROMPT


SYSTEM_PROMPT = load_system_prompt()


def load_tokens() -> dict:
    """Maps each user's app token to the user's name; the organization issues the tokens."""
    if not TOKENS_FILE.exists():
        return {}
    return json.loads(TOKENS_FILE.read_text(encoding="utf-8"))


class Usage:
    """Counts each user's requests per day, so one user cannot spend the organization's budget."""

    def __init__(self, path: Path):
        self.lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS usage (user TEXT, day TEXT, requests INTEGER,"
            " input_tokens INTEGER, output_tokens INTEGER, PRIMARY KEY (user, day))"
        )
        self.db.commit()

    def take(self, user: str) -> bool:
        day = time.strftime("%Y-%m-%d")
        with self.lock:
            row = self.db.execute("SELECT requests FROM usage WHERE user = ? AND day = ?", (user, day)).fetchone()
            if row and row[0] >= DAILY_REQUESTS:
                return False
            self.db.execute(
                "INSERT INTO usage VALUES (?, ?, 1, 0, 0) ON CONFLICT(user, day) DO UPDATE SET requests = requests + 1",
                (user, day),
            )
            self.db.commit()
            return True

    def record(self, user: str, input_tokens: int, output_tokens: int) -> None:
        day = time.strftime("%Y-%m-%d")
        with self.lock:
            self.db.execute(
                "UPDATE usage SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ? WHERE user = ? AND day = ?",
                (input_tokens, output_tokens, user, day),
            )
            self.db.commit()


usage = Usage(USAGE_DB)


class ChatMessage(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(max_length=100_000)


class ChatRequest(BaseModel):
    messages: list[ChatMessage] = Field(min_length=1)


def event(payload: dict) -> str:
    return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"


@app.get("/health")
async def health() -> dict:
    return {"ok": True, "model": MODEL}


@app.post("/v1/chat")
async def chat(body: ChatRequest, request: Request, authorization: str = Header(default="")):
    token = authorization.removeprefix("Bearer ").strip()
    user = load_tokens().get(token)
    if not token or user is None:
        raise HTTPException(status_code=401, detail="unknown token")
    if not usage.take(user):
        raise HTTPException(status_code=429, detail="daily limit reached")

    history = [m.model_dump() for m in body.messages][-MAX_HISTORY_MESSAGES:]
    if history[0]["role"] != "user":
        history = history[1:]
    if not history or history[-1]["role"] != "user":
        raise HTTPException(status_code=400, detail="the conversation must end with the user's message")

    async def stream():
        try:
            async with client.beta.messages.stream(
                model=MODEL,
                max_tokens=MAX_TOKENS,
                # Same system prompt on every request, so it stays in the prompt cache.
                system=[{"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}],
                messages=history,
                output_config={"effort": EFFORT},
                # On a safety decline Claude Opus 5.5 hands the request to a fallback model in the same call.
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            ) as response:
                async for text in response.text_stream:
                    if await request.is_disconnected():
                        # The user pressed stop: leaving the block closes the request to Claude.
                        return
                    yield event({"type": "delta", "text": text})
                final = await response.get_final_message()
            usage.record(user, final.usage.input_tokens, final.usage.output_tokens)
            if final.stop_reason == "refusal":
                yield event({"type": "error", "code": "refusal", "message": "The assistant cannot help with this request."})
                return
            yield event({"type": "done", "stop_reason": final.stop_reason})
        except anthropic.RateLimitError:
            yield event({"type": "error", "code": "busy", "message": "The assistant is busy. Try again in a moment."})
        except anthropic.APIConnectionError:
            yield event({"type": "error", "code": "network", "message": "The assistant could not be reached."})
        except anthropic.APIStatusError as e:
            log.error("Claude API error %s: %s", e.status_code, e.message)
            yield event({"type": "error", "code": "server", "message": "The assistant had a problem. Try again."})

    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
