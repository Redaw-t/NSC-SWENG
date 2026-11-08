-- =====================================================================
--  DEMO / TEST DATA - development databases only. NEVER run on a store's database.
--
--  Run AFTER NickelSales-Database-Config.sql (the schema). The setup scripts
--  (nickel-sales/setup.*, setup-database.*) run both for developers; the
--  installer runs only the schema and creates the store's own admin account.
--
--  The demo users all have the published password Password123, so on a real
--  store they would be a way in for anyone. The self-tests in test/ rely on
--  these users and the test catalog below.
--  INSERT IGNORE: skips what already exists, so this script can be re-run safely
--  and never overwrites a price or stock figure changed later.
-- =====================================================================

USE `NickelSales_Database`;

INSERT IGNORE INTO `Users` (UserID, UserLoginName, UserPassword, UserName, UserLastname, UserRole, UserStatus) VALUES
	("NSC-00001", "JCubillan", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Jerick", "Cubillan", "A;C;I", "Active"),
    ("NSC-00002", "JDelaCruz", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Juan", "Dela Cruz", "C", "Active"),
    ("NSC-00003", "MSantaAna", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Maria", "Santa Ana", "I", "Active"),
    ("NSC-00004", "RSantos", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Rhian", "Santos", "C", "Inactive"),
    ("NSC-00005", "KRodriguez", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Kimberly", "Rodriguez", "C", "Locked");

-- TEST CATALOG so POS has something to sell. The self-tests in test/ rely on exactly these
-- (test/stubs/TestCatalog resets them before each scenario).
INSERT IGNORE INTO `Category` (CategoryName) VALUES
    ('School/Office Supplies'), ('Modiste Items'), ('Groceries'); -- Finalize the store's starting categories with the partner before production.

INSERT IGNORE INTO `Supplier` (SupplierName, ContactPerson, Phone) VALUES
    ('Sample Supplier', 'Test Contact', '0000-000-0001');

INSERT IGNORE INTO `Product`
    (ProductID, Barcode, ProductName, Unit, RetailPrice, WholesalePrice, Vatable, IsActive, CategoryID, SupplierID, StockOnHand)
VALUES
    ('P-0001', '4800000000019', 'Bond Paper A4 (ream)', 'ream', 250.00, 235.00, TRUE, TRUE,
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'School/Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 40),
    ('P-0002', '4800000000026', 'Ballpen Black', 'pc', 12.00, 10.00, TRUE, TRUE,
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'School/Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 200),
    ('P-0003', '4800000000033', 'Notebook 80 leaves', 'pc', 45.00, NULL, TRUE, TRUE,              -- no wholesale price
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'School/Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 60),
    ('P-0004', '4800000000040', 'Rice (per kg)', 'kg', 52.50, 50.00, FALSE, TRUE,                 -- VAT-exempt, sold by weight
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'Groceries'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 100.500),
    ('P-0005', '4800000000057', 'Masking Tape 1 inch', 'roll', 35.75, 32.00, TRUE, TRUE,          -- out of stock
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'School/Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 0),
    ('P-0006', '4800000000064', 'Desk Calculator', 'pc', 499.00, NULL, TRUE, FALSE,               -- inactive (no longer sold)
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'School/Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 5),
    ('P-0007', NULL, 'Glue Stick', 'pc', 18.00, 16.50, TRUE, TRUE,                                -- no barcode: found by search only
        (SELECT CategoryID FROM `Category` WHERE CategoryName = 'School/Office Supplies'),
        (SELECT SupplierID FROM `Supplier` WHERE SupplierName = 'Sample Supplier'), 30);

-- Starting stock for the test catalog (Inventory is the source of truth for stock).
INSERT IGNORE INTO `Inventory` (ProductID, QuantityOnHand, ReorderLevel, UpdatedAt) VALUES
    ('P-0001', 40, 0, UTC_TIMESTAMP(3)),
    ('P-0002', 200, 0, UTC_TIMESTAMP(3)),
    ('P-0003', 60, 0, UTC_TIMESTAMP(3)),
    ('P-0004', 100.500, 0, UTC_TIMESTAMP(3)),
    ('P-0005', 0, 0, UTC_TIMESTAMP(3)),
    ('P-0006', 5, 0, UTC_TIMESTAMP(3)),
    ('P-0007', 30, 0, UTC_TIMESTAMP(3));
