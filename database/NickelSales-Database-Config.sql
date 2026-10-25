CREATE DATABASE IF NOT EXISTS `NickelSales_Database`;

USE `NickelSales_Database`;

-- ===================== CORE RECORDS =====================
CREATE TABLE IF NOT EXISTS `Users` (
	UserID VARCHAR(10) PRIMARY KEY,
    UserLoginName VARCHAR(30) NOT NULL UNIQUE,
    UserPassword VARCHAR(60) NOT NULL, -- Bcrypt
    UserName VARCHAR(30) NOT NULL,
    UserLastname VARCHAR(30) NOT NULL,
    UserRole VARCHAR(5) NOT NULL, -- Admin = A / Cashier = C / Inventory = I
    UserStatus VARCHAR(8) NOT NULL, -- Active/Inactive/Locked
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- INSERT IGNORE: skips users that already exist, so this script can be re-run safely.
INSERT IGNORE INTO `Users` (UserID, UserLoginName, UserPassword, UserName, UserLastname, UserRole, UserStatus) VALUES
	("NSC-00001", "JCubillan", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Jerick", "Cubillan", "A;C;I", "Active"),
    ("NSC-00002", "JDelaCruz", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Juan", "Dela Cruz", "C", "Active"),
    ("NSC-00003", "MSantaAna", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Maria", "Santa Ana", "I", "Active"),
    ("NSC-00004", "RSantos", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Rhian", "Santos", "C", "Inactive"),
    ("NSC-00005", "KRodriguez", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Kimberly", "Rodriguez", "C", "Locked");

-- ================== TRANSACTION RECORDS ==================
-- Owned by POS. Money is DECIMAL(12,2), quantities DECIMAL(12,3), times are UTC.
-- Only COMPLETED sales are stored, and a stored sale is never changed.

CREATE TABLE IF NOT EXISTS `Sale` (
    SaleID CHAR(36) PRIMARY KEY,                 -- UUID, unique across all terminals
    InvoiceNo VARCHAR(20) NOT NULL UNIQUE,       -- e.g. T01-2026-000123
    TerminalID VARCHAR(10) NOT NULL,
    CashierUserID VARCHAR(10) NOT NULL,
    CompletedAt DATETIME(3) NOT NULL,            -- UTC
    VatableSales DECIMAL(12,2) NOT NULL,
    VatExemptSales DECIMAL(12,2) NOT NULL,
    VatAmount DECIMAL(12,2) NOT NULL,
    Total DECIMAL(12,2) NOT NULL,
    SyncedAt DATETIME(3) NULL,                   -- NULL = not yet sent by Sync
    FOREIGN KEY (CashierUserID) REFERENCES `Users`(UserID),
    INDEX idx_sale_unsynced (SyncedAt, CompletedAt),
    INDEX idx_sale_completed (CompletedAt)       -- reports / history by date range
);

CREATE TABLE IF NOT EXISTS `SaleLine` (
    SaleID CHAR(36) NOT NULL,
    LineNo INT NOT NULL,
    ProductID VARCHAR(20) NOT NULL,              -- copied at sale time (no link to the product tables)
    Barcode VARCHAR(50) NULL,
    ProductName VARCHAR(100) NOT NULL,
    Unit VARCHAR(10) NOT NULL,
    Quantity DECIMAL(12,3) NOT NULL,
    UnitPrice DECIMAL(12,2) NOT NULL,            -- VAT-inclusive
    PriceType VARCHAR(10) NOT NULL,              -- RETAIL / WHOLESALE
    Vatable BOOLEAN NOT NULL,
    LineTotal DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (SaleID, LineNo),
    FOREIGN KEY (SaleID) REFERENCES `Sale`(SaleID),
    INDEX idx_saleline_product (ProductID)       -- product reports
);

CREATE TABLE IF NOT EXISTS `Payment` (
    SaleID CHAR(36) NOT NULL,
    PaymentNo INT NOT NULL,
    Method VARCHAR(20) NOT NULL,                 -- CASH / GCASH / MAYA / QRPH
    ReferenceNo VARCHAR(50) NULL,                -- GCash/Maya/QRPh reference; NULL for cash
    AmountTendered DECIMAL(12,2) NOT NULL,
    AmountApplied DECIMAL(12,2) NOT NULL,
    ChangeGiven DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (SaleID, PaymentNo),
    FOREIGN KEY (SaleID) REFERENCES `Sale`(SaleID)
);

-- Next invoice number per terminal per year (year in Philippine time).
CREATE TABLE IF NOT EXISTS `InvoiceSequence` (
    TerminalID VARCHAR(10) NOT NULL,
    InvoiceYear SMALLINT NOT NULL,
    NextNumber INT NOT NULL,
    PRIMARY KEY (TerminalID, InvoiceYear)
);

-- ============ UPGRADES FOR DATABASES MADE BY AN OLDER SCRIPT ============
-- CREATE TABLE IF NOT EXISTS never changes a table that is already there, so
-- columns and indexes added after a table was first created are added here,
-- only when missing. Safe to run any number of times. Add new ones the same way.

-- Payment.ReferenceNo (POS: GCash / Maya / QRPh reference numbers)
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Payment' AND COLUMN_NAME = 'ReferenceNo');
SET @ddl = IF(@missing, 'ALTER TABLE `Payment` ADD COLUMN ReferenceNo VARCHAR(50) NULL AFTER Method', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;

-- Sale.idx_sale_completed (POS: reports / history by date range)
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Sale' AND INDEX_NAME = 'idx_sale_completed');
SET @ddl = IF(@missing, 'ALTER TABLE `Sale` ADD INDEX idx_sale_completed (CompletedAt)', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;

-- SaleLine.idx_saleline_product (POS: product reports)
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'SaleLine' AND INDEX_NAME = 'idx_saleline_product');
SET @ddl = IF(@missing, 'ALTER TABLE `SaleLine` ADD INDEX idx_saleline_product (ProductID)', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;
