-- =====================================================================
-- V6 — The first administrator
--
-- One account, holding only administration permissions. It cannot raise,
-- approve or post anything: FR-SEC-13 and the SoD rules in V5 put access
-- management and transactional rights in different hands, and the first
-- account is not an exception to that.
--
-- The password must be changed on first login. Change it immediately in
-- any environment that is not a developer laptop.
-- =====================================================================

INSERT INTO app_user (username, full_name, email, password_hash,
                      home_branch_id, is_active, must_change_password)
SELECT 'admin',
       'System Administrator',
       NULL,
       -- BCrypt(12) of 'ChangeMe#2026'
       '$2b$12$zxXLBj/MP437ktgOkv5bfeb4.edwfADqrBS4K3UU1aziKP1BNpLF6',
       b.id,
       TRUE,
       TRUE
  FROM branch b
 WHERE b.code = 'KGL';

INSERT INTO user_role (user_id, role_id, branch_id, valid_from)
SELECT u.id, r.id, NULL, CURRENT_DATE
  FROM app_user u, role r
 WHERE u.username = 'admin' AND r.code = 'SYS_ADMIN';

-- The account's own creation is the first line of the audit trail.
INSERT INTO audit_log (entity_name, entity_id, entity_label, action,
                       after_state, actor_name, actor_username, actor_role, reason)
SELECT 'app_user', u.id, 'User account · admin', 'CREATE',
       jsonb_build_object('username', 'admin',
                          'full_name', 'System Administrator',
                          'is_active', true,
                          'roles', jsonb_build_array('System Administrator')),
       'System', 'system', 'Installation',
       'Seeded by migration V6 on first installation.'
  FROM app_user u WHERE u.username = 'admin';
