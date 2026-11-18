# Phogram assistant proxy

The service between Phogram's assistant tab and Claude. The app sends a conversation with the user's app
token; the service checks the token and the user's daily quota, asks Claude with the organization's key, and
streams the reply back as server-sent events. Closing the connection (the app's stop button) stops the reply.

The organization's API key lives only in this service's environment, never in the app.

## Run

```bash
python -m venv .venv
.venv/bin/pip install -r requirements.txt
export ANTHROPIC_API_KEY=...        # the organization's key
.venv/bin/uvicorn app:app --host 127.0.0.1 --port 8080
```

Put it behind HTTPS (for example nginx with a certificate) and turn response buffering off for `/v1/chat`
so the reply reaches the app as it is written.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `ASSISTANT_MODEL` | `claude-opus-5-5` | The model. `claude-sonnet-5-5` and `claude-haiku-5-5` cost less. |
| `ASSISTANT_EFFORT` | `medium` | How much the model thinks: `low`, `medium`, `high`. |
| `ASSISTANT_MAX_TOKENS` | `64000` | Longest reply. |
| `ASSISTANT_DAILY_REQUESTS` | `200` | Requests per user per day. |
| `ASSISTANT_MAX_HISTORY` | `60` | Messages of a conversation sent with each request. |
| `ASSISTANT_TOKENS_FILE` | `tokens.json` | `{"<app token>": "<user name>"}`, issued by the organization. |
| `ASSISTANT_USAGE_DB` | `usage.sqlite3` | Requests and tokens per user per day. |
| `ASSISTANT_SYSTEM_PROMPT_FILE` | `system_prompt.txt` | The assistant's instructions; a built-in default if missing. |

Safety declines: the service turns on Claude's server-side fallback, so a declined request is retried on
another model in the same call; a decline that remains reaches the app as an error event.
