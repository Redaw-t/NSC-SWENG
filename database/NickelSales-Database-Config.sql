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
    LastSignIn TIMESTAMP NULL DEFAULT NULL, -- set on every successful login
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- No users are seeded: the store's first account, the administrator NSC-00001, is created
-- by Installation Setup on the Main POS (username and password chosen there).

-- ===================== PRODUCT CATALOG =====================
-- Owned by Product. Money is DECIMAL(12,2), quantities DECIMAL(12,3).
-- Products, categories and suppliers are never deleted, only deactivated
-- (IsActive = FALSE), so old sales and receipts can always be explained.
-- No categories, suppliers or products are seeded: the store adds its own.

CREATE TABLE IF NOT EXISTS `Category` (
    CategoryID INT AUTO_INCREMENT PRIMARY KEY,
    CategoryName VARCHAR(50) NOT NULL,
    IsActive BOOLEAN NOT NULL DEFAULT TRUE,      -- FALSE = hidden from the product form; products keep it
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_category_name (CategoryName)   -- upper/lower case is ignored
);

CREATE TABLE IF NOT EXISTS `Supplier` (
    SupplierID INT AUTO_INCREMENT PRIMARY KEY,
    SupplierName VARCHAR(100) NOT NULL,
    ContactPerson VARCHAR(100) NULL,
    Phone VARCHAR(30) NULL,
    Email VARCHAR(100) NULL,
    Address VARCHAR(200) NULL,
    IsActive BOOLEAN NOT NULL DEFAULT TRUE,      -- FALSE = hidden from the product form; products keep it
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_supplier_name (SupplierName)   -- upper/lower case is ignored
);

CREATE TABLE IF NOT EXISTS `Product` (
    ProductID VARCHAR(20) PRIMARY KEY,           -- e.g. P-0001 (SaleLine.ProductID is a copy of it)
    Barcode VARCHAR(50) NULL,                    -- NULL = no barcode; any number of NULLs is allowed
    ProductName VARCHAR(100) NOT NULL,
    Unit VARCHAR(10) NOT NULL,                   -- pc, box, kg ...
    RetailPrice DECIMAL(12,2) NOT NULL,          -- before VAT
    WholesalePrice DECIMAL(12,2) NULL,           -- before VAT; NULL = no wholesale price
    Vatable BOOLEAN NOT NULL DEFAULT TRUE,       -- FALSE = VAT-exempt
    IsActive BOOLEAN NOT NULL DEFAULT TRUE,      -- FALSE = discontinued: POS will not sell it
    CategoryID INT NULL,
    SupplierID INT NULL,                         -- (no stock column: stock lives in Inventory.QuantityOnHand)
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_product_barcode (Barcode),
    INDEX idx_product_name (ProductName),        -- cashier search and sorted lists
    CONSTRAINT fk_product_category FOREIGN KEY (CategoryID) REFERENCES `Category`(CategoryID),
    CONSTRAINT fk_product_supplier FOREIGN KEY (SupplierID) REFERENCES `Supplier`(SupplierID),
    CONSTRAINT chk_product_prices CHECK (RetailPrice >= 0 AND (WholesalePrice IS NULL OR WholesalePrice >= 0))
);

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
    OriginalPrice DECIMAL(12,2) NULL,            -- NULL = normal; else the price before the cashier's F4 Edit Price
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

-- Voids / returns of a completed sale (POS: Void Item and Void Transaction on a retrieved sale).
-- The Sale rows are never changed: each void is a Return with its ReturnLines, and the sale's
-- invoice shows them below the sold items. ReturnNo = InvoiceNo + "-01", "-02" ... (e.g. T01-2026-000123-01)
-- (one number per void of that sale). Amounts are positive (what was refunded); stock goes back.
-- RETURN is a reserved word in MySQL: always write the table name as `Return`.
CREATE TABLE IF NOT EXISTS `Return` (
    ReturnID CHAR(36) PRIMARY KEY,               -- UUID, unique across all terminals
    ReturnNo VARCHAR(40) NOT NULL UNIQUE,        -- e.g. T01-2026-000123-01
    SaleID CHAR(36) NOT NULL,                    -- the sale voided from
    InvoiceNo VARCHAR(20) NOT NULL,              -- copy of Sale.InvoiceNo
    TerminalID VARCHAR(10) NOT NULL,             -- terminal the void was done on
    CashierUserID VARCHAR(10) NOT NULL,          -- who did the void
    ReturnedAt DATETIME(3) NOT NULL,             -- UTC
    FullVoid BOOLEAN NOT NULL,                   -- TRUE = nothing is left on the sale after this return
    RefundMethod VARCHAR(20) NOT NULL,           -- CASH / GCASH / MAYA / QRPH (chosen by the cashier; may differ from the sale's)
    RefundReference VARCHAR(50) NULL,            -- GCash/Maya/QRPh reference of the refund transfer; NULL for cash
    VatableSales DECIMAL(12,2) NOT NULL,
    VatExemptSales DECIMAL(12,2) NOT NULL,
    VatAmount DECIMAL(12,2) NOT NULL,
    Total DECIMAL(12,2) NOT NULL,                -- refunded, VAT included
    SyncedAt DATETIME(3) NULL,                   -- NULL = not yet sent by Sync
    FOREIGN KEY (SaleID) REFERENCES `Sale`(SaleID),
    FOREIGN KEY (CashierUserID) REFERENCES `Users`(UserID),
    INDEX idx_return_sale (SaleID),
    INDEX idx_return_returned (ReturnedAt)
);

CREATE TABLE IF NOT EXISTS `ReturnLine` (
    ReturnID CHAR(36) NOT NULL,
    LineNo INT NOT NULL,
    SaleID CHAR(36) NOT NULL,                    -- the sale (transaction) the item was sold on
    SaleLineNo INT NOT NULL,                     -- which line of that sale
    ReturnNo VARCHAR(40) NOT NULL,               -- copy of Return.ReturnNo (the return reference)
    ProductID VARCHAR(20) NOT NULL,              -- copied from the sale line
    Barcode VARCHAR(50) NULL,
    ProductName VARCHAR(100) NOT NULL,
    Unit VARCHAR(10) NOT NULL,
    Quantity DECIMAL(12,3) NOT NULL,             -- voided quantity (positive)
    UnitPrice DECIMAL(12,2) NOT NULL,            -- VAT-inclusive, as sold
    PriceType VARCHAR(10) NOT NULL,
    Vatable BOOLEAN NOT NULL,
    LineTotal DECIMAL(12,2) NOT NULL,            -- refunded for this line (positive)
    PRIMARY KEY (ReturnID, LineNo),
    FOREIGN KEY (ReturnID) REFERENCES `Return`(ReturnID),
    FOREIGN KEY (SaleID, SaleLineNo) REFERENCES `SaleLine`(SaleID, LineNo),
    INDEX idx_returnline_sale (SaleID, SaleLineNo),
    INDEX idx_returnline_product (ProductID)
);

-- Next invoice number per terminal per year (year in Philippine time).
CREATE TABLE IF NOT EXISTS `InvoiceSequence` (
    TerminalID VARCHAR(10) NOT NULL,
    InvoiceYear SMALLINT NOT NULL,
    NextNumber INT NOT NULL,
    PRIMARY KEY (TerminalID, InvoiceYear)
);


-- Quantities are DECIMAL(12,3), times are UTC DATETIME(3).
-- ProductID matches SaleLine.ProductID (VARCHAR(20)). There is no foreign key to the Product
-- table yet: add one once the Product table is in this script (agree it with the Product owner).
-- Current stock. A product gets its row the first time it is stocked.
CREATE TABLE IF NOT EXISTS `Inventory` (
    ProductID VARCHAR(20) PRIMARY KEY,
    QuantityOnHand DECIMAL(12,3) NOT NULL DEFAULT 0,
    ReorderLevel DECIMAL(12,3) NOT NULL DEFAULT 0,
    UpdatedAt DATETIME(3) NOT NULL,
    CONSTRAINT chk_inventory_quantity CHECK (QuantityOnHand >= 0),   -- stock can never go negative
    CONSTRAINT chk_inventory_reorder CHECK (ReorderLevel >= 0)
);

-- Every change to stock, newest last. Only ever added to, never edited or deleted.
CREATE TABLE IF NOT EXISTS `StockMovement` (
    MovementID BIGINT AUTO_INCREMENT PRIMARY KEY,
    ProductID VARCHAR(20) NOT NULL,
    MovementType VARCHAR(12) NOT NULL,           -- STOCK_IN / STOCK_OUT / ADJUSTMENT / SALE / RETURN
    Quantity DECIMAL(12,3) NOT NULL,             -- SIGNED: +20 stock-in, -2 sale
    QuantityBefore DECIMAL(12,3) NOT NULL,
    QuantityAfter DECIMAL(12,3) NOT NULL,
    Reason VARCHAR(20) NULL,                     -- SALE / DAMAGE / TRANSFER / COUNT_CORRECTION / OTHER
    ReferenceNo VARCHAR(50) NULL,                -- invoice number for a SALE, return number for a RETURN
    Note VARCHAR(200) NULL,                      -- the stock-in "Reference / Note", adjustment note
    PerformedBy VARCHAR(10) NULL,                -- UserID; NULL for sales for now
    CreatedAt DATETIME(3) NOT NULL,
    INDEX idx_movement_product (ProductID, CreatedAt),
    INDEX idx_movement_created (CreatedAt)       -- history list, newest first
);

-- ================== AUDIT AND SYSTEM ==================
-- Owned by System. Times are UTC DATETIME(3).
-- Every audit event any module sends (logins, user, product, price and stock changes, sales),
-- one row each. Only ever added to, never edited or deleted: the app has no update or delete for it.
CREATE TABLE IF NOT EXISTS `ActivityLog` (
    LogID BIGINT AUTO_INCREMENT PRIMARY KEY,
    CreatedAt DATETIME(3) NOT NULL,
    UserID VARCHAR(30) NULL,                     -- who did it; the typed login name for a failed login
    TerminalID VARCHAR(10) NOT NULL,             -- which POS it happened on (T01, T02 ...)
    Module VARCHAR(20) NOT NULL,                 -- Login / Users / Products / Inventory / POS / System / Other
    ActionType VARCHAR(40) NOT NULL,             -- e.g. LOGIN_SUCCESS, PRICE_CHANGED, STOCK_ADJUSTED
    Target VARCHAR(50) NULL,                     -- what it was done to: a user ID, product ID, invoice number ...
    Details VARCHAR(255) NULL,                   -- the rest of the event, as sent
    OldValue VARCHAR(255) NULL,                  -- before a change (e.g. prices), if the event says
    NewValue VARCHAR(255) NULL,                  -- after the change
    INDEX idx_activity_created (CreatedAt),
    INDEX idx_activity_user (UserID, CreatedAt),
    INDEX idx_activity_module (Module, CreatedAt)
);

-- One row per attempt to send a record to another terminal. Written by Sync (through
-- system.contract.SyncLogWriter); only its status, retry count and error change afterwards.
CREATE TABLE IF NOT EXISTS `SyncLog` (
    LogID BIGINT AUTO_INCREMENT PRIMARY KEY,
    CreatedAt DATETIME(3) NOT NULL,
    UpdatedAt DATETIME(3) NOT NULL,
    SourceTerminal VARCHAR(10) NOT NULL,
    TargetTerminal VARCHAR(10) NOT NULL,
    DataType VARCHAR(20) NOT NULL,               -- SALE / PRODUCT / USER
    RecordID VARCHAR(50) NOT NULL,               -- the sale, product or user sent
    Status VARCHAR(10) NOT NULL,                 -- PENDING / SYNCED / FAILED
    RetryCount INT NOT NULL DEFAULT 0,
    ErrorMessage VARCHAR(255) NULL,
    INDEX idx_sync_status (Status, CreatedAt),
    INDEX idx_sync_created (CreatedAt)
);

-- Every backup and restore made from the System screen, successful or not.
CREATE TABLE IF NOT EXISTS `BackupRecord` (
    BackupID BIGINT AUTO_INCREMENT PRIMARY KEY,
    CreatedAt DATETIME(3) NOT NULL,
    TerminalID VARCHAR(10) NOT NULL,
    Kind VARCHAR(10) NOT NULL,                   -- BACKUP / SAFETY (taken before a restore) / RESTORE
    FileName VARCHAR(255) NOT NULL,
    FolderPath VARCHAR(500) NOT NULL,
    SizeBytes BIGINT NULL,
    Result VARCHAR(10) NOT NULL,                 -- SUCCESS / FAILED
    Message VARCHAR(255) NULL,
    UserID VARCHAR(10) NULL,
    INDEX idx_backup_created (CreatedAt)
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

-- SaleLine.OriginalPrice (POS: F4 Edit Price; NULL = price not edited, so old sales stay as they were)
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'SaleLine' AND COLUMN_NAME = 'OriginalPrice');
SET @ddl = IF(@missing, 'ALTER TABLE `SaleLine` ADD COLUMN OriginalPrice DECIMAL(12,2) NULL AFTER UnitPrice', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;

-- Return.RefundReference (POS: reference number of a GCash / Maya / QRPh refund)
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Return' AND COLUMN_NAME = 'RefundReference');
SET @ddl = IF(@missing, 'ALTER TABLE `Return` ADD COLUMN RefundReference VARCHAR(50) NULL AFTER RefundMethod', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;

-- Users.LastSignIn (Auth: time of the user's last successful sign in)
SET @missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Users' AND COLUMN_NAME = 'LastSignIn');
SET @ddl = IF(@missing, 'ALTER TABLE `Users` ADD COLUMN LastSignIn TIMESTAMP NULL DEFAULT NULL AFTER UserStatus', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;

-- Product.StockOnHand removed (stock is handled by Inventory: Inventory.QuantityOnHand is the only stock figure).
-- Its CHECK constraint goes first, since MySQL won't drop a column a constraint still uses.
SET @present = (SELECT COUNT(*) > 0 FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Product' AND CONSTRAINT_NAME = 'chk_product_stock');
SET @ddl = IF(@present, 'ALTER TABLE `Product` DROP CHECK chk_product_stock', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;
SET @present = (SELECT COUNT(*) > 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Product' AND COLUMN_NAME = 'StockOnHand');
SET @ddl = IF(@present, 'ALTER TABLE `Product` DROP COLUMN StockOnHand', 'DO 0');
PREPARE upgrade FROM @ddl; EXECUTE upgrade; DEALLOCATE PREPARE upgrade;
