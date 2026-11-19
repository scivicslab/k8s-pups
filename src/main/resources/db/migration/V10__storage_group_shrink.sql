-- A shrink of a group's storage that an administrator prepared and has not continued or cancelled yet. The storage is
-- taken away while it is pending (state 'revoked', PV recorded), because the Lustre project quota that enforces the
-- size can only be lowered by a file system administrator, outside the controller. shrink_to is the size the group gets
-- when the shrink is continued; NULL when no shrink is pending.
--
-- Billing (storage_reservation_change, written by the trigger storage_group_record of V8): preparing writes one
-- 'revoke' row (0 bytes), as taking the storage away does; continuing sets state 'active' and the new size in one
-- UPDATE, which writes one 'restore' row with the new size. Cancelling changes neither state nor size and writes none.
ALTER TABLE storage_group ADD COLUMN shrink_to TEXT;
ALTER TABLE storage_group ADD CONSTRAINT storage_group_shrink_only_when_revoked
    CHECK (shrink_to IS NULL OR state = 'revoked');

-- Audit events (audit_event.event_type): group.shrink.prepare, group.shrink, group.shrink.cancel; the group name and
-- the old and new size in detail.
