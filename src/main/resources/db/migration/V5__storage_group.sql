-- Storage an administrator gives to a group (a laboratory): one Lustre CSI PVC per group, which the Pods of every
-- member can mount next to the member's own storage. Users cannot create groups or add themselves; only an
-- administrator does. A group is never deleted: taking its storage away sets state to 'revoked' and keeps the PV
-- values, so the same directory can be given back, as for storage_allocation.
CREATE TABLE storage_group (
    name             TEXT PRIMARY KEY,
    storage_type     TEXT NOT NULL DEFAULT 'lustre-csi',
    size             TEXT NOT NULL,
    state            TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'revoked')),
    created_by       TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by       TEXT,
    revoked_at       TIMESTAMPTZ,
    pv_name          TEXT,
    pv_storage_class TEXT,
    pv_capacity      TEXT,
    pv_csi_driver    TEXT,
    pv_volume_handle TEXT,
    pv_volume_attributes JSONB
);

-- Who may mount a group's storage, and how: 'rw' read-write, 'ro' read-only.
CREATE TABLE storage_group_member (
    group_name TEXT NOT NULL REFERENCES storage_group (name),
    user_name  TEXT NOT NULL,
    mode       TEXT NOT NULL DEFAULT 'rw' CHECK (mode IN ('rw', 'ro')),
    added_by   TEXT NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_name, user_name)
);
CREATE INDEX storage_group_member_user_idx ON storage_group_member (user_name);

-- Audit events (audit_event.event_type): group.create, group.resize, group.revoke, group.restore, group.member.add,
-- group.member.remove; the group name and the member's mode in detail.
