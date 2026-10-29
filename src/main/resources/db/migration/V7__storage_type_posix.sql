-- The storage types lustre-csi (a007) and nfs-k8s (W206) were one kind of storage on two clusters: a shared POSIX file
-- system. They are now one type, posix; what backs it on a cluster is the setting k8spups.posix.backend, not the key.
UPDATE storage_allocation SET storage_type = 'posix' WHERE storage_type IN ('lustre-csi', 'nfs-k8s');

ALTER TABLE storage_group ALTER COLUMN storage_type SET DEFAULT 'posix';
UPDATE storage_group SET storage_type = 'posix' WHERE storage_type IN ('lustre-csi', 'nfs-k8s');

-- tool_mount_preference.mounts: [{"kind": "storage", "name": <storage type>, "path": ...}, ...]
UPDATE tool_mount_preference SET mounts = (
    SELECT COALESCE(jsonb_agg(
        CASE WHEN e ->> 'kind' = 'storage' AND e ->> 'name' IN ('lustre-csi', 'nfs-k8s')
             THEN jsonb_set(e, '{name}', '"posix"') ELSE e END ORDER BY ord), '[]'::jsonb)
    FROM jsonb_array_elements(mounts) WITH ORDINALITY AS t(e, ord))
WHERE mounts::text LIKE '%lustre-csi%' OR mounts::text LIKE '%nfs-k8s%';
