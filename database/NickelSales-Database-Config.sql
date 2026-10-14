CREATE DATABASE IF NOT EXISTS `NickelSales_Database`;

USE `NickelSales_Database`;

-- ===================== CORE RECORDS =====================
CREATE TABLE IF NOT EXISTS `Users` (
	UserID VARCHAR(10) PRIMARY KEY,
    UserPassword VARCHAR(60) NOT NULL, -- Bcrypt
    UserName VARCHAR(30) NOT NULL,
    UserLastname VARCHAR(30) NOT NULL,
    UserRole VARCHAR(5) NOT NULL, -- Admin = A / Cashier = C / Inventory = I
    UserStatus VARCHAR(8) NOT NULL, -- Active/Inactive/Locked
    CreatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UpdatedAt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO `Users` (UserID, UserPassword, UserName, UserLastname, UserRole, UserStatus) VALUES
	("NSC-00001", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Jerick", "Cubillan", "A;C;I", "Active"),
    ("NSC-00002", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Juan", "Dela Cruz", "C", "Active"),
    ("NSC-00003", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Maria", "Santa Ana", "I", "Active"),
    ("NSC-00004", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Rhian", "Santos", "C", "Inactive"),
    ("NSC-00005", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Kimberly", "Rodriguez", "C", "Locked");

INSERT INTO `Users` (UserID, UserPassword, UserName, UserLastname, UserRole, UserStatus) VALUES
	("NSC-00006", "$2a$10$TjKMuyc2JIk0Kx/PfVAjkeGLmg3O2twxiX.3iCLg1OqhS762FzYSW", "Face To Face", "Demo", "A;C;I", "Active");
-- ================== TRANSACTION RECORDS ==================