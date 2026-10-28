-- Storage the administrators give to users. The controller creates a user's PVC only for an active allocation, with
-- the type and size written here; users cannot create, expand, delete or share storage themselves.
-- A row is never deleted: revoking sets state to 'revoked' and keeps the PV values, so an administrator can give the
-- same directory back (only for a type whose StorageClass keeps the PV after the PVC is deleted, reclaimPolicy Retain).
CREATE TABLE storage_allocation (
    id               BIGSERIAL PRIMARY KEY,
    user_name        TEXT NOT NULL,
    storage_type     TEXT NOT NULL,            -- a key of k8spups.storage-type-options: lustre-csi, longhorn, nfs-k8s, ...
    size             TEXT NOT NULL,            -- Kubernetes quantity, e.g. 50Gi
    state            TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'revoked')),
    granted_by       TEXT NOT NULL,            -- the administrator, or 'controller' for the default allocation
    granted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by       TEXT,
    revoked_at       TIMESTAMPTZ,
    restored_from    BIGINT REFERENCES storage_allocation (id),   -- the revoked allocation whose PV this one reuses
    -- The PV that the PVC was bound to, recorded once the PVC is bound. With these values a PV can be written again
    -- after the PV object itself was deleted (checked on a007 with the Lustre CSI driver exa.csi.ddn.com).
    pv_name          TEXT,
    pv_storage_class TEXT,
    pv_capacity      TEXT,
    pv_csi_driver    TEXT,
    pv_volume_handle TEXT,
    pv_volume_attributes JSONB
);

-- At most one active allocation of a type per user: the PVC name pups-data-<user key>-<type> is per user and type.
CREATE UNIQUE INDEX storage_allocation_active_idx ON storage_allocation (user_name, storage_type) WHERE state = 'active';
CREATE INDEX storage_allocation_user_idx ON storage_allocation (user_name, granted_at DESC);

-- The mounts a user chose for a tool the last time, offered again at the next launch. Each element of mounts is
-- {"kind": "storage" | "dataset", "name": <storage type or dataset name>, "path": <mount path in the Pod>}.
-- At each launch the controller checks every element against the user's current allocations and dataset grants and
-- drops the ones that are no longer allowed.
CREATE TABLE tool_mount_preference (
    user_name  TEXT NOT NULL,
    tool       TEXT NOT NULL,
    mounts     JSONB NOT NULL DEFAULT '[]',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_name, tool)
);

-- Audit events of this table (audit_event.event_type): storage.allocate, storage.resize, storage.revoke, storage.restore,
-- with the storage type and size in detail.
