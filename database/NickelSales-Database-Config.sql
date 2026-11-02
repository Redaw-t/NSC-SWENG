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

-- ===================== PRODUCT CATALOG =====================
-- Owned by Product. Money is DECIMAL(12,2), quantities DECIMAL(12,3).
-- Products, categories and suppliers are never deleted, only deactivated
-- (IsActive = FALSE), so old sales and receipts can always be explained.

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
    SupplierID INT NULL,
    StockOnHand DECIMAL(12,3) NOT NULL DEFAULT 0, -- READ by Product, WRITTEN only by Inventory (stock in / sale stock-out)
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_product_barcode (Barcode),
    INDEX idx_product_name (ProductName),        -- cashier search and sorted lists
    CONSTRAINT fk_product_category FOREIGN KEY (CategoryID) REFERENCES `Category`(CategoryID),
    CONSTRAINT fk_product_supplier FOREIGN KEY (SupplierID) REFERENCES `Supplier`(SupplierID),
    CONSTRAINT chk_product_prices CHECK (RetailPrice >= 0 AND (WholesalePrice IS NULL OR WholesalePrice >= 0)),
    CONSTRAINT chk_product_stock CHECK (StockOnHand >= 0)
);

-- TEST CATALOG so POS has something to sell. The self-tests in test/ rely on exactly these nine
-- (test/stubs/TestCatalog resets them before each scenario), so run them on a development database.
-- INSERT IGNORE: skips what already exists, so this script can be re-run safely and never
-- overwrites a price or stock figure changed later. Delete or deactivate these in a real store.
INSERT IGNORE INTO `Category` (CategoryName) VALUES
    ('School/Office Supplies'), ('Modiste Items'), ('Groceries'); -- Remove Groceries before Production. Finalize the initial lists of categories with partner before production.

INSERT IGNORE INTO `Supplier` (SupplierName, ContactPerson, Phone) VALUES
    ('Sample Supplier', 'Test Contact', '0000-000-0001');

INSERT IGNORE INTO `Product`
    (ProductID, Barcode, ProductName, Unit, RetailPrice, WholesalePrice, Vatable, IsActive, CategoryID, SupplierID, StockOnHand)
VALUES
    ('P-0001', '4800000000019', 'Bond Paper A4 (ream)', 'ream', 250.00, 235.00, TRUE, TRUE,
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 40),
    ('P-0002', '4800000000026', 'Ballpen Black', 'pc', 12.00, 10.00, TRUE, TRUE,
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 200),
    ('P-0003', '4800000000033', 'Notebook 80 leaves', 'pc', 45.00, NULL, TRUE, TRUE,              -- no wholesale price
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 60),
    ('P-0004', '4800000000040', 'Rice (per kg)', 'kg', 52.50, 50.00, FALSE, TRUE,                 -- VAT-exempt, sold by weight
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Groceries'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 100.500),
    ('P-0005', '4800000000057', 'Masking Tape 1 inch', 'roll', 35.75, 32.00, TRUE, TRUE,          -- out of stock
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 0),
    ('P-0006', '4800000000064', 'Desk Calculator', 'pc', 499.00, NULL, TRUE, FALSE,               -- inactive (no longer sold)
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 5),
    ('P-0007', NULL, 'Glue Stick', 'pc', 18.00, 16.50, TRUE, TRUE,                                -- no barcode: found by search only
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 30);

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
    MovementType VARCHAR(12) NOT NULL,           -- STOCK_IN / STOCK_OUT / ADJUSTMENT / SALE
    Quantity DECIMAL(12,3) NOT NULL,             -- SIGNED: +20 stock-in, -2 sale
    QuantityBefore DECIMAL(12,3) NOT NULL,
    QuantityAfter DECIMAL(12,3) NOT NULL,
    Reason VARCHAR(20) NULL,                     -- SALE / DAMAGE / TRANSFER / COUNT_CORRECTION / OTHER
    ReferenceNo VARCHAR(50) NULL,                -- invoice number for a SALE
    Note VARCHAR(200) NULL,                      -- the stock-in "Reference / Note", adjustment note
    PerformedBy VARCHAR(10) NULL,                -- UserID; NULL for sales for now
    CreatedAt DATETIME(3) NOT NULL,
    INDEX idx_movement_product (ProductID, CreatedAt),
    INDEX idx_movement_created (CreatedAt)       -- history list, newest first
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
