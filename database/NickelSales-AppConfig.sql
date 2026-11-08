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
CREATE TABLE IF NOT EXISTS `AppSettings` (
    SettingKey VARCHAR(50) PRIMARY KEY,
    SettingValue VARCHAR(200) NULL,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- ============ UPGRADES FOR DATABASES MADE BY AN OLDER SCRIPT ============

-- PosTerminals.ComputerName
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'PosTerminals' AND COLUMN_NAME = 'ComputerName');
SET @ddl = IF(@missing, 'ALTER TABLE `PosTerminals` ADD COLUMN ComputerName VARCHAR(100) NULL AFTER IPAddress', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;
