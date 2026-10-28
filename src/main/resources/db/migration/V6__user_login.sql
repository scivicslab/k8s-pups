-- When each user last opened the dashboard. An administrator adds group members by name without asking LDAP, so the
-- name may be one that has never logged in (not registered yet, or mistyped); the member list shows that.
CREATE TABLE user_login (
    user_name   TEXT PRIMARY KEY,
    first_login TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login  TIMESTAMPTZ NOT NULL DEFAULT now()
);
