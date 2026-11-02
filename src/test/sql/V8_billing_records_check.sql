-- Manual check of V8__pod_usage_and_storage_reservation.sql (the billing records). It writes rows, so run it on a
-- disposable PostgreSQL 17 only, never on the database of a running controller:
--
--   kubectl create namespace scratch-sql-check
--   kubectl run pg -n scratch-sql-check --image=postgres:17 --env=POSTGRES_PASSWORD=scratch --env=POSTGRES_DB=t --restart=Never
--   for f in src/main/resources/db/migration/V*.sql; do
--     kubectl exec -i -n scratch-sql-check pg -- psql -U postgres -d t -v ON_ERROR_STOP=1 -q < "$f"; done
--   kubectl exec -i -n scratch-sql-check pg -- psql -U postgres -d t < src/test/sql/V8_billing_records_check.sql
--   kubectl delete namespace scratch-sql-check
--
-- Expected: the lines "ERROR" appear exactly where the section says "expect"; every other statement succeeds.
-- Part 1 checks the triggers and the two views; part 2 checks the statements of PodUsageRepository.

\set ON_ERROR_STOP off
\echo '--- 1. triggers on storage_allocation'
INSERT INTO storage_allocation (user_name, storage_type, size, granted_by) VALUES ('alice','posix','50Gi','admin');
UPDATE storage_allocation SET size='500Gi' WHERE user_name='alice' AND state='active';
UPDATE storage_allocation SET state='revoked', revoked_by='admin', revoked_at=now() WHERE user_name='alice' AND state='active';
SELECT owner_kind, owner_name, storage_type, size, size_bytes, event FROM storage_reservation_change WHERE owner_name='alice' ORDER BY id;

\echo '--- 2. triggers on storage_group'
INSERT INTO storage_group (name, size, created_by) VALUES ('labx','1Ti','admin');
UPDATE storage_group SET size='2Ti' WHERE name='labx';
UPDATE storage_group SET state='revoked', revoked_by='admin', revoked_at=now() WHERE name='labx';
UPDATE storage_group SET state='active', revoked_by=NULL, revoked_at=NULL WHERE name='labx';
SELECT owner_kind, owner_name, size, size_bytes, event FROM storage_reservation_change WHERE owner_name='labx' ORDER BY id;

\echo '--- 3. unsupported size is refused (expect an error, and no allocation row)'
INSERT INTO storage_allocation (user_name, storage_type, size, granted_by) VALUES ('bob','posix','500GB','admin');
SELECT count(*) AS bob_rows FROM storage_allocation WHERE user_name='bob';

\echo '--- 4. daily view: (a) 10:00 to 23:00 JST on one day => 1 day'
INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event) VALUES
 ('2026-11-02 10:00+09','user','ta','posix','50Gi',quantity_to_bytes('50Gi'),'allocate'),
 ('2026-11-02 23:00+09','user','ta','posix','0',0,'revoke');
SELECT owner_name, day, reserved_bytes/1024^3 AS gib FROM storage_reservation_daily WHERE owner_name='ta' ORDER BY day;

\echo '--- 5. daily view: (b) 50Gi from 11-02 10:00, 500Gi from 11-03 15:00, revoked 11-05 00:00 JST sharp'
INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event) VALUES
 ('2026-11-02 10:00+09','user','tb','posix','50Gi',quantity_to_bytes('50Gi'),'allocate'),
 ('2026-11-03 15:00+09','user','tb','posix','500Gi',quantity_to_bytes('500Gi'),'resize'),
 ('2026-11-05 00:00+09','user','tb','posix','0',0,'revoke');
SELECT owner_name, day, reserved_bytes/1024^3 AS gib FROM storage_reservation_daily WHERE owner_name='tb' ORDER BY day;

\echo '--- 6. daily view: (c) given and taken away, then given back later the same day => 1 day, larger size'
INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event) VALUES
 ('2026-11-02 01:00+09','user','tc','posix','20Gi',quantity_to_bytes('20Gi'),'allocate'),
 ('2026-11-02 02:00+09','user','tc','posix','0',0,'revoke'),
 ('2026-11-02 22:00+09','user','tc','posix','100Gi',quantity_to_bytes('100Gi'),'restore'),
 ('2026-11-03 00:00:01+09','user','tc','posix','0',0,'revoke');
SELECT owner_name, day, reserved_bytes/1024^3 AS gib FROM storage_reservation_daily WHERE owner_name='tc' ORDER BY day;

\echo '--- 7. storage_reservation_change is append-only (expect 3 errors)'
UPDATE storage_reservation_change SET size='1' WHERE owner_name='ta';
DELETE FROM storage_reservation_change WHERE owner_name='ta';
TRUNCATE storage_reservation_change;

\echo '--- 8. pod_usage: create, ready, end; then try to tamper (expect 6 errors)'
INSERT INTO pod_usage (session_id, user_name, tool, pod_name, profile, requests_cpu_millicores, limits_cpu_millicores, created_at)
 VALUES ('s1','alice','jupyter-lab','pups-s1','standard',250,8000,'2026-11-02 09:59:00+09');
UPDATE pod_usage SET ready_at='2026-11-02 10:00:00+09', last_seen_at=now() WHERE session_id='s1';
UPDATE pod_usage SET ended_at='2026-11-02 12:30:00+09', end_reason='stopped by the user' WHERE session_id='s1';
SELECT session_id, billed_seconds, core_seconds FROM pod_usage_billable WHERE session_id='s1';
UPDATE pod_usage SET user_name='mallory' WHERE session_id='s1';
UPDATE pod_usage SET limits_cpu_millicores=1 WHERE session_id='s1';
UPDATE pod_usage SET ready_at='2026-11-02 11:00:00+09' WHERE session_id='s1';
UPDATE pod_usage SET ended_at='2026-11-02 13:00:00+09' WHERE session_id='s1';
DELETE FROM pod_usage WHERE session_id='s1';
TRUNCATE pod_usage;

\echo '--- 9. pod_usage: an open row can be seen again (heartbeat) and closed once'
INSERT INTO pod_usage (session_id, user_name, tool, pod_name, created_at) VALUES ('s2','carol','file-browser','pups-s2', now());
UPDATE pod_usage SET last_seen_at = now() WHERE session_id='s2' AND ended_at IS NULL;
UPDATE pod_usage SET ended_at = now(), end_reason='x' WHERE session_id='s2' AND ended_at IS NULL;
SELECT session_id, ended_at IS NOT NULL AS closed FROM pod_usage WHERE session_id='s2';

-- ===== part 2: the statements of PodUsageRepository =====
\echo '--- open twice => one row'
INSERT INTO pod_usage (session_id, user_name, tool, pod_name, profile, requests_cpu_millicores, limits_cpu_millicores, created_at)
 VALUES ('s10','dave','jupyter-lab','pups-s10','standard',250,8000,'2026-11-02 09:00+09') ON CONFLICT (session_id) DO NOTHING;
INSERT INTO pod_usage (session_id, user_name, tool, pod_name, profile, requests_cpu_millicores, limits_cpu_millicores, created_at)
 VALUES ('s10','dave','jupyter-lab','pups-s10','standard',250,8000,'2026-11-02 09:00+09') ON CONFLICT (session_id) DO NOTHING;
SELECT count(*) AS rows FROM pod_usage WHERE session_id='s10';
\echo '--- ready twice => written once (second update touches 0 rows)'
UPDATE pod_usage SET ready_at='2026-11-02 09:01+09', last_seen_at=now() WHERE session_id='s10' AND ready_at IS NULL AND ended_at IS NULL;
UPDATE pod_usage SET ready_at='2026-11-02 09:59+09', last_seen_at=now() WHERE session_id='s10' AND ready_at IS NULL AND ended_at IS NULL;
SELECT ready_at FROM pod_usage WHERE session_id='s10';
\echo '--- seen: open rows only'
UPDATE pod_usage SET last_seen_at = now() WHERE ended_at IS NULL AND session_id = ANY(ARRAY['s10','nope']);
\echo '--- end with a time before ready_at => clamped to ready_at; second end touches 0 rows'
UPDATE pod_usage SET ended_at = GREATEST('2026-11-02 08:00+09'::timestamptz, created_at, ready_at), end_reason='first' WHERE session_id='s10' AND ended_at IS NULL;
UPDATE pod_usage SET ended_at = GREATEST('2026-11-02 12:00+09'::timestamptz, created_at, ready_at), end_reason='second' WHERE session_id='s10' AND ended_at IS NULL;
SELECT ended_at, end_reason FROM pod_usage WHERE session_id='s10';
\echo '--- endOpenExcept: s11 is gone, s12 was taken over'
INSERT INTO pod_usage (session_id, user_name, tool, pod_name, created_at, ready_at, last_seen_at) VALUES
 ('s11','u','t','p11','2026-11-02 10:00+09','2026-11-02 10:01+09','2026-11-02 10:30+09'),
 ('s12','u','t','p12','2026-11-02 10:00+09',NULL,'2026-11-02 10:30+09');
UPDATE pod_usage SET ended_at = GREATEST(last_seen_at, created_at, ready_at), end_reason = 'lost'
 WHERE ended_at IS NULL AND NOT (session_id = ANY(ARRAY['s12']));
SELECT session_id, ended_at, end_reason FROM pod_usage WHERE session_id IN ('s11','s12') ORDER BY session_id;
\echo '--- endOpenExcept with nothing taken over closes every open row'
UPDATE pod_usage SET ended_at = GREATEST(last_seen_at, created_at, ready_at), end_reason = 'lost'
 WHERE ended_at IS NULL AND NOT (session_id = ANY(ARRAY[]::text[]));
SELECT count(*) AS still_open FROM pod_usage WHERE ended_at IS NULL;
