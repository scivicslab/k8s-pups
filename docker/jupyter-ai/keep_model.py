"""Keeps the chat model that Jupyter AI remembers usable after the deployment changes its models.

Jupyter AI keeps the model the user last chose in config.json under the user's own Jupyter data directory, and prefers it
to the default that the Pod passes in (JUPYTER_AI_MODEL). When the OpenAI-compatible server (the GPU broker) no longer
offers that model, every question to the chat fails with 404 until the user finds the model list and chooses another.

At the start of the Pod this asks the server which models it offers (GET <JUPYTER_AI_BASE_URL>/models). If the remembered
chat model is not among them, model_provider_id is set to the default model. Nothing else in config.json is touched, and a
model that the server still offers is kept. When the server cannot be asked, nothing is changed: not knowing is not a
reason to throw away the user's choice.

Usage: python3 keep_model.py [config.json]   (default: <Jupyter data dir>/jupyter_ai/config.json)
"""
import json
import os
import sys
import tempfile
import urllib.request

PROVIDER = "openrouter:"  # how entrypoint.sh names the models of the OpenAI-compatible server


def say(message):
    print("jupyter-ai: " + message, file=sys.stderr)


def default_config_path():
    data = os.environ.get("JUPYTER_DATA_DIR") or os.path.join(os.path.expanduser("~"), ".local", "share", "jupyter")
    return os.path.join(data, "jupyter_ai", "config.json")


def offered_models(base_url, timeout=5):
    """The ids of the models the server offers; None when it cannot be asked or answers something else."""
    try:
        with urllib.request.urlopen(base_url.rstrip("/") + "/models", timeout=timeout) as response:
            body = json.load(response)
        ids = [m["id"] for m in body["data"] if isinstance(m, dict) and isinstance(m.get("id"), str)]
        return ids or None
    except Exception as e:  # unreachable, not JSON, no "data": all the same to the user: leave the choice alone
        say("the model list of " + base_url + " could not be read (" + e.__class__.__name__ + "); the chosen model stays")
        return None


def keep_model(config_path, base_url, default_model):
    """Returns the model_provider_id that is in config.json after the call, or None when there is no config or no change was possible."""
    if not os.path.isfile(config_path) or not base_url or not default_model:
        return None
    try:
        with open(config_path, encoding="utf-8") as f:
            config = json.load(f)
    except Exception as e:
        say(config_path + " could not be read (" + e.__class__.__name__ + "); left as it is")
        return None
    chosen = config.get("model_provider_id") if isinstance(config, dict) else None
    if not isinstance(chosen, str) or not chosen.startswith(PROVIDER):
        return chosen  # no choice, or a model of another provider: not the server's to judge
    offered = offered_models(base_url)
    if offered is None or chosen[len(PROVIDER):] in offered:
        return chosen
    replacement = PROVIDER + default_model
    config["model_provider_id"] = replacement
    directory = os.path.dirname(config_path)
    fd, temporary = tempfile.mkstemp(dir=directory, prefix=".config-", suffix=".json")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=2, ensure_ascii=False)
        os.replace(temporary, config_path)
    except Exception:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise
    say("the chat model " + chosen[len(PROVIDER):] + " is not offered by the server any more; now " + default_model)
    return replacement


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else default_config_path()
    try:
        keep_model(path, os.environ.get("JUPYTER_AI_BASE_URL", ""), os.environ.get("JUPYTER_AI_MODEL", ""))
    except Exception as e:  # the Pod must start whatever happens here
        say("could not check the chat model: " + e.__class__.__name__ + ": " + str(e))
