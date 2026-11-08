-- What a user typed into the launch form of a tool the last time (the fields of ToolPlugin.userParameters(), for example
-- the document folders of Documents), so that the next launch shows it again. params: {"<ENV_VAR_NAME>": "<value>"}.
-- A field that the tool declares secret (UserParameter.secret) is never stored. The row is replaced at each successful
-- launch, a blank value included.
CREATE TABLE tool_form_preference (
    user_name  TEXT NOT NULL,
    tool       TEXT NOT NULL,
    params     JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_name, tool)
);
