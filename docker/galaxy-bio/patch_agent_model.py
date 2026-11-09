"""Makes the AI agents of Galaxy send their tool definitions to an OpenAI-compatible server without "strict".

Galaxy builds the model of an agent as OpenAIChatModel(name, provider=...), and pydantic-ai then marks tool definitions
"strict": true. A server behind the GPU broker (vLLM) does not honour that reliably: with a model such as
qwen3.8-flash-next, about one call in eight to a tool WITHOUT parameters (get_server_info, get_user_info, ...) comes back as
{"arguments": "{}"} instead of {}. pydantic-ai refuses the extra key (extra_forbidden), sends the error back as a retry
prompt, the model repeats the call it sees in its history, and after three retries the router gives up with
"Tool 'get_server_info' exceeded max retries count of 3" and the user reads the fallback text. The same requests without
the strict flags were answered correctly 24 times of 24.

The patch gives the model a profile that does not send "strict". Usage: python3 patch_agent_model.py [base.py]
It fails when the line it changes is not there (a new Galaxy version needs a look), and does nothing when already patched.
"""
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "/galaxy/server/lib/galaxy/agents/base.py"
OLD = "        return OpenAIChatModel(model_name, provider=openai_provider)\n"
NEW = (
    "        # k8s-pups: the OpenAI-compatible server of the deployment does not honour strict tool definitions (see\n"
    "        # patch_agent_model.py in the image build): a tool without parameters is then called with {\"arguments\": \"{}\"}.\n"
    "        return OpenAIChatModel(\n"
    "            model_name,\n"
    "            provider=openai_provider,\n"
    "            profile=OpenAIModelProfile(openai_supports_strict_tool_definition=False),\n"
    "        )\n"
)
IMPORT_AFTER = "from pydantic_ai.models.openai import OpenAIChatModel\n"
IMPORT_NEW = IMPORT_AFTER + "from pydantic_ai.profiles.openai import OpenAIModelProfile\n"

text = open(PATH, encoding="utf-8").read()
if "openai_supports_strict_tool_definition" in text:
    print("already patched:", PATH)
    sys.exit(0)
if text.count(OLD) != 1 or text.count(IMPORT_AFTER) != 1:
    sys.exit("patch_agent_model.py: the lines to change are not in " + PATH + " (OLD x%d, import x%d)" % (text.count(OLD), text.count(IMPORT_AFTER)))
text = text.replace(OLD, NEW).replace(IMPORT_AFTER, IMPORT_NEW)
open(PATH, "w", encoding="utf-8").write(text)
print("patched:", PATH)
