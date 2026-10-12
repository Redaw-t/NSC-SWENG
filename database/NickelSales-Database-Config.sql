CREATE DATABASE IF NOT EXISTS `NickelSales_Database`;

USE `NickelSales_Database`;

-- ===================== CORE RECORDS =====================
CREATE TABLE IF NOT EXISTS `Users` (
	UserID VARCHAR(10) PRIMARY KEY,
    UserPassword VARCHAR(60) NOT NULL, -- Bcrypt
    UserName VARCHAR(30) NOT NULL,
    UserLastname VARCHAR(30) NOT NULL,
    UserRole VARCHAR(5) NOT NULL, -- Admin = A / Cashier = C / Inventory = I
    UserStatus VARCHAR(8) NOT NULL -- Active/Inactive
);

INSERT INTO `Users` VALUES
	("NSC-00001", "Password", "Jerick", "Cubillan", "A;C;I", "Active"),
    ("NSC-00002", "Password", "Juan", "Dela Cruz", "C", "Active"),
    ("NSC-00003", "Password", "Maria", "Santa Ana", "I", "Active");

-- ================== TRANSACTION RECORDS ==================