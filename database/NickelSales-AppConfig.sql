-- =====================================================================
--  Nickel Sales Center - app configuration and state
--
--  A database of its own, separate from NickelSales_Database: it holds how
--  this installation of the app is set up, not the store's records. Applied
--  by setup.bat / setup.sh together with NickelSales-Database-Config.sql.
--  Safe to run any number of times: it only creates what is missing and never
--  overwrites a value that was set later.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `nickelsales_appconfig`;

USE `nickelsales_appconfig`;

-- One row per POS terminal (computer running the app).
-- T01 is always the Main POS. On the Main POS this table lists every terminal it
-- has handed an ID to; on an Additional POS it holds T01 (the Main POS's address)
-- and its own row.
-- T01's IPAddress NULL = this installation has not been set up yet: the app shows
-- the Installation Setup screens instead of the login page until it is.
CREATE TABLE IF NOT EXISTS `PosTerminals` (
    TerminalID VARCHAR(10) PRIMARY KEY,          -- T01 = Main POS, T02, T03 ... = Additional POS
    IPAddress VARCHAR(45) NULL,                  -- the terminal's address on the store network (45 fits IPv6)
    ComputerName VARCHAR(100) NULL,              -- Windows computer name, to recognise a terminal asking again
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- The Main POS, not set up yet. INSERT IGNORE keeps an address saved earlier.
INSERT IGNORE INTO `PosTerminals` (TerminalID, IPAddress) VALUES ('T01', NULL);

-- This installation's own settings, as name / value pairs:
--   ThisTerminalID  which terminal THIS computer is (T01, T02 ...); used in invoice numbers
--   ApiKey          the developer's API key, sent to the Main POS on every API call
--   AutoPrintReceipt  POS Settings: 'true' = print receipts straight away (no preview); missing = off
--   SyncAppliedSeq  sync: the last change in the Main POS's list (SyncFeed.Seq) applied on this PC
CREATE TABLE IF NOT EXISTS `AppSettings` (
    SettingKey VARCHAR(50) PRIMARY KEY,
    SettingValue VARCHAR(200) NULL,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- =========================== SYNC BETWEEN TERMINALS ===========================
-- Kept here, not in NickelSales_Database, so they are never in a backup or in the copy a new
-- Additional POS gets: each PC has its own queue and its own place in the Main POS's list.
-- (Other settings sync uses, in AppSettings: SyncAppliedSeq = the last change in the Main POS's
-- list that this PC has applied.)

-- Every change made on THIS PC to a synced table, written in the same transaction as the change.
-- The sync worker sends them to the Main POS, oldest first; SentAt is set once the Main POS has
-- them. Sent rows are kept 30 days, then deleted.
CREATE TABLE IF NOT EXISTS `SyncOutbox` (
    OutboxID BIGINT AUTO_INCREMENT PRIMARY KEY,
    ChangeID CHAR(36) NOT NULL,                  -- UUID: the change's ID on every terminal
    Kind VARCHAR(20) NOT NULL,                   -- SALE / RETURN / STOCK / ACTIVITY / STORE / PRODUCT / CATEGORY / SUPPLIER / USER / REORDER
    RecordID VARCHAR(50) NOT NULL,               -- what it is about: invoice no., product ID ...
    Payload MEDIUMTEXT NOT NULL,                 -- the rows, as JSON (shared.SyncOutbox)
    CreatedAt DATETIME(3) NOT NULL,              -- UTC
    SentAt DATETIME(3) NULL,                     -- UTC; NULL = not sent yet
    UNIQUE KEY uq_outbox_change (ChangeID),
    INDEX idx_outbox_unsent (SentAt, OutboxID)
) ENGINE=InnoDB;

-- Main POS only: every terminal's changes (its own included) in ONE order, numbered by Seq. Every
-- PC applies them in this order, so all end up the same. Kept 90 days, then deleted.
CREATE TABLE IF NOT EXISTS `SyncFeed` (
    Seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    ChangeID CHAR(36) NOT NULL,
    SourceTerminal VARCHAR(10) NOT NULL,         -- where the change was made (T01, T02 ...)
    Kind VARCHAR(20) NOT NULL,
    RecordID VARCHAR(50) NOT NULL,
    Payload MEDIUMTEXT NOT NULL,
    CreatedAt DATETIME(3) NOT NULL,              -- UTC, when it was made on its terminal
    AcceptedAt DATETIME(3) NOT NULL,             -- UTC, when the Main POS took it
    UNIQUE KEY uq_feed_change (ChangeID),
    INDEX idx_feed_accepted (AcceptedAt)
) ENGINE=InnoDB;

-- A change from another terminal that could not be applied here (e.g. a barcode this PC already
-- gives to another product). Shown in Settings > Sync Logs as Failed; Retry applies it again.
CREATE TABLE IF NOT EXISTS `SyncFailed` (
    SyncLogID BIGINT PRIMARY KEY,                -- its line in NickelSales_Database.SyncLog
    Seq BIGINT NOT NULL,
    ChangeID CHAR(36) NOT NULL,
    SourceTerminal VARCHAR(10) NOT NULL,
    Kind VARCHAR(20) NOT NULL,
    RecordID VARCHAR(50) NOT NULL,
    Payload MEDIUMTEXT NOT NULL,
    FailedAt DATETIME(3) NOT NULL
) ENGINE=InnoDB;

-- Main POS only: the next ID it will hand out for new products, users, categories and suppliers
-- (to itself or to an Additional POS), so two terminals never make the same one.
CREATE TABLE IF NOT EXISTS `IdCounter` (
    Kind VARCHAR(20) PRIMARY KEY,                -- PRODUCT / USER / CATEGORY / SUPPLIER
    NextValue BIGINT NOT NULL
) ENGINE=InnoDB;

-- ============ UPGRADES FOR DATABASES MADE BY AN OLDER SCRIPT ============

-- PosTerminals.ComputerName
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'PosTerminals' AND COLUMN_NAME = 'ComputerName');
SET @ddl = IF(@missing, 'ALTER TABLE `PosTerminals` ADD COLUMN ComputerName VARCHAR(100) NULL AFTER IPAddress', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;
