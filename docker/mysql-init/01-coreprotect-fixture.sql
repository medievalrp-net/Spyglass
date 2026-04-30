-- Synthetic CoreProtect 22 schema + small fixture, used to validate
-- the importer's MySQL source path end-to-end. Schema paraphrased from
-- CoreProtect's GPL Database.java (see docs/importer.md for the
-- clean-room discipline note); not a verbatim copy.

CREATE DATABASE IF NOT EXISTS coreprotect
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE coreprotect;

-- ===== Lookup tables ============================================

CREATE TABLE IF NOT EXISTS co_world (
    rowid INT AUTO_INCREMENT PRIMARY KEY,
    id    INT NOT NULL,
    world VARCHAR(255) NOT NULL,
    UNIQUE KEY (id)
);

CREATE TABLE IF NOT EXISTS co_user (
    rowid INT AUTO_INCREMENT PRIMARY KEY,
    time  INT NOT NULL,
    user  VARCHAR(100) NOT NULL,
    uuid  VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS co_material_map (
    rowid    INT AUTO_INCREMENT PRIMARY KEY,
    id       INT NOT NULL,
    material VARCHAR(255) NOT NULL,
    UNIQUE KEY (id)
);

CREATE TABLE IF NOT EXISTS co_blockdata_map (
    rowid INT AUTO_INCREMENT PRIMARY KEY,
    id    INT NOT NULL,
    data  VARCHAR(255) NOT NULL,
    UNIQUE KEY (id)
);

CREATE TABLE IF NOT EXISTS co_entity_map (
    rowid  INT AUTO_INCREMENT PRIMARY KEY,
    id     INT NOT NULL,
    entity VARCHAR(255) NOT NULL,
    UNIQUE KEY (id)
);

-- ===== Event tables =============================================

CREATE TABLE IF NOT EXISTS co_block (
    rowid       BIGINT AUTO_INCREMENT PRIMARY KEY,
    time        INT NOT NULL,
    user        INT NOT NULL,
    wid         INT NOT NULL,
    x           INT NOT NULL,
    y           INT NOT NULL,
    z           INT NOT NULL,
    type        INT NOT NULL,
    data        INT NOT NULL DEFAULT 0,
    meta        MEDIUMBLOB,
    blockdata   BLOB,
    action      TINYINT NOT NULL,
    rolled_back TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS co_container (
    rowid       INT AUTO_INCREMENT PRIMARY KEY,
    time        INT NOT NULL,
    user        INT NOT NULL,
    wid         INT NOT NULL,
    x           INT NOT NULL,
    y           INT NOT NULL,
    z           INT NOT NULL,
    type        INT NOT NULL,
    data        INT NOT NULL DEFAULT 0,
    amount      INT NOT NULL,
    metadata    BLOB,
    action      TINYINT NOT NULL,
    rolled_back TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS co_chat (
    rowid   INT AUTO_INCREMENT PRIMARY KEY,
    time    INT NOT NULL,
    user    INT NOT NULL,
    wid     INT NOT NULL,
    x       INT NOT NULL,
    y       INT NOT NULL,
    z       INT NOT NULL,
    message VARCHAR(16000) NOT NULL
);

CREATE TABLE IF NOT EXISTS co_command (
    rowid   INT AUTO_INCREMENT PRIMARY KEY,
    time    INT NOT NULL,
    user    INT NOT NULL,
    wid     INT NOT NULL,
    x       INT NOT NULL,
    y       INT NOT NULL,
    z       INT NOT NULL,
    message VARCHAR(16000) NOT NULL
);

CREATE TABLE IF NOT EXISTS co_session (
    rowid  INT AUTO_INCREMENT PRIMARY KEY,
    time   INT NOT NULL,
    user   INT NOT NULL,
    wid    INT NOT NULL,
    x      INT NOT NULL,
    y      INT NOT NULL,
    z      INT NOT NULL,
    action TINYINT NOT NULL
);

CREATE TABLE IF NOT EXISTS co_item (
    rowid       INT AUTO_INCREMENT PRIMARY KEY,
    time        INT NOT NULL,
    user        INT NOT NULL,
    wid         INT NOT NULL,
    x           INT NOT NULL,
    y           INT NOT NULL,
    z           INT NOT NULL,
    type        INT NOT NULL,
    data        BLOB,
    amount      INT NOT NULL,
    action      TINYINT NOT NULL,
    rolled_back TINYINT NOT NULL DEFAULT 0
);

-- ===== Fixture data =============================================
-- One world, two players, a handful of events across every table the
-- importer reads. Keeps the live-MySQL test self-contained (no large
-- import needed; we just want to prove the JDBC code path works).

-- Worlds, materials, block-states, entities.
INSERT INTO co_world (id, world) VALUES
    (1, 'world');

INSERT INTO co_material_map (id, material) VALUES
    (1, 'minecraft:stone'),
    (2, 'minecraft:diamond_ore'),
    (3, 'minecraft:oak_log'),
    (4, 'minecraft:chest'),
    (5, 'minecraft:bread');

INSERT INTO co_blockdata_map (id, data) VALUES
    (1, 'axis=y'),
    (2, 'facing=north');

INSERT INTO co_entity_map (id, entity) VALUES
    (1, 'cow'),
    (2, 'pig');

-- Players. uuid populated for both — the importer warns + skips on null UUID.
INSERT INTO co_user (rowid, time, user, uuid) VALUES
    (1, 1700000000, 'TestAlice', '11111111-1111-1111-1111-111111111111'),
    (2, 1700000000, 'TestBob',   '22222222-2222-2222-2222-222222222222');

-- co_block rows: break, place, use, kill (entity), kill (player).
INSERT INTO co_block (time, user, wid, x, y, z, type, data, action, blockdata) VALUES
    (1700000010, 1, 1, 100, 64, 100, 1, 0, 0, NULL),                  -- break stone
    (1700000020, 1, 1, 100, 64, 100, 3, 0, 1, CAST('1' AS BINARY)),   -- place oak_log[axis=y]
    (1700000030, 2, 1, 101, 64, 100, 4, 0, 2, NULL),                  -- use chest
    (1700000040, 1, 1, 102, 64, 100, 1, 0, 3, NULL),                  -- kill entity (cow, type=1 in entity_map)
    (1700000050, 1, 1, 103, 64, 100, 0, 2, 3, NULL);                  -- kill player (Bob, data=co_user.rowid=2)

-- co_session: login + logout for Alice.
INSERT INTO co_session (time, user, wid, x, y, z, action) VALUES
    (1700000000, 1, 1, 0, 70, 0, 1),  -- login
    (1700000900, 1, 1, 50, 70, 50, 0); -- logout

-- co_chat + co_command.
INSERT INTO co_chat (time, user, wid, x, y, z, message) VALUES
    (1700000100, 1, 1, 50, 70, 50, 'hello world'),
    (1700000110, 2, 1, 51, 70, 50, 'how are you');

INSERT INTO co_command (time, user, wid, x, y, z, message) VALUES
    (1700000200, 1, 1, 50, 70, 50, '/spyglass help');

-- co_container: deposit + withdraw on a chest.
INSERT INTO co_container (time, user, wid, x, y, z, type, data, amount, action) VALUES
    (1700000300, 1, 1, 101, 64, 100, 5, 0, 8, 1),   -- deposit 8 bread
    (1700000310, 1, 1, 101, 64, 100, 5, 0, 3, 0);   -- withdraw 3 bread

-- co_item: drop + pickup.
INSERT INTO co_item (time, user, wid, x, y, z, type, amount, action, data) VALUES
    (1700000400, 1, 1, 50, 70, 50, 5, 5, 2, NULL),  -- drop bread
    (1700000410, 2, 1, 50, 70, 50, 5, 5, 3, NULL);  -- pickup bread

-- Read-only role for the importer's MySQL credentials.
CREATE USER IF NOT EXISTS 'spyglass_reader'@'%' IDENTIFIED BY 'readonly';
GRANT SELECT ON coreprotect.* TO 'spyglass_reader'@'%';
FLUSH PRIVILEGES;
