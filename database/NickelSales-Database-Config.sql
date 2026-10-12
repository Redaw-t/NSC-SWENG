CREATE DATABASE IF NOT EXISTS `NickelSales_Database`;

USE `nickelsales_database`;

-- ===================== CORE RECORDS =====================
CREATE TABLE IF NOT EXISTS `Users` (
	UserID VARCHAR(20) PRIMARY KEY,
    UserName VARCHAR(30) NOT NULL,
    UserLastname VARCHAR(30) NOT NULL,
    UserRole VARCHAR(9) NOT NULL, -- Admin/Cashier/Inventory
    UserStatus VARCHAR(8) NOT NULL, -- Active/Inactive
    PRIMARY KEY (UserID)
);

-- ================== TRANSACTION RECORDS ==================