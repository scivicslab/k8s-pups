-- The storage types lustre-csi (a007) and nfs-k8s (W206) were one kind of storage on two clusters: a shared POSIX file
-- system. They are now one type, posix; what backs it on a cluster is the setting k8spups.posix.backend, not the key.
UPDATE storage_allocation SET storage_type = 'posix' WHERE storage_type IN ('lustre-csi', 'nfs-k8s');

ALTER TABLE storage_group ALTER COLUMN storage_type SET DEFAULT 'posix';
UPDATE storage_group SET storage_type = 'posix' WHERE storage_type IN ('lustre-csi', 'nfs-k8s');

-- tool_mount_preference.mounts: {"mounts": [{"path": ..., "value": ...}, ...], "primary": <storage type>}. A storage type
-- appears as a whole JSON string, so replacing the quoted key is exact.
UPDATE tool_mount_preference
SET mounts = replace(replace(mounts::text, '"lustre-csi"', '"posix"'), '"nfs-k8s"', '"posix"')::jsonb
WHERE mounts::text LIKE '%"lustre-csi"%' OR mounts::text LIKE '%"nfs-k8s"%';
