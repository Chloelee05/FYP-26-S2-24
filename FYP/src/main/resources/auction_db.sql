-- phpMyAdmin SQL Dump
-- version 5.2.3
-- https://www.phpmyadmin.net/
--
-- Host: 127.0.0.1:3306
-- Generation Time: Apr 22, 2026 at 03:58 AM
-- Server version: 9.6.0
-- PHP Version: 8.3.28

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- Database: `auction_db`
--
CREATE DATABASE IF NOT EXISTS `auction_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `auction_db`;

-- --------------------------------------------------------

--
-- Table structure for table `auction`
--

DROP TABLE IF EXISTS `auction`;
CREATE TABLE IF NOT EXISTS `auction` (
  `auction_id` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  `status_id` tinyint UNSIGNED NOT NULL,
  `seller_id` bigint UNSIGNED NOT NULL,
  `date_created` datetime NOT NULL,
  `date_end` datetime NOT NULL,
  `auction_type` tinyint UNSIGNED NOT NULL,
  PRIMARY KEY (`auction_id`),
  KEY `seller_id_foreign` (`seller_id`),
  KEY `auction_status_foreign` (`status_id`),
  KEY `auction_type_foreign` (`auction_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `auction_details`
--

DROP TABLE IF EXISTS `auction_details`;
CREATE TABLE IF NOT EXISTS `auction_details` (
  `ID` bigint UNSIGNED NOT NULL,
  `title` varchar(255) NOT NULL,
  `description` longtext NOT NULL,
  `item_condition_id` tinyint NOT NULL,
  `winning_bid` int DEFAULT NULL,
  `winner_id` int DEFAULT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `auction_images`
--

DROP TABLE IF EXISTS `auction_images`;
CREATE TABLE IF NOT EXISTS `auction_images` (
  `ID` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  `auction_id` bigint UNSIGNED NOT NULL,
  `image_url` longtext NOT NULL,
  `upload_date` datetime NOT NULL,
  PRIMARY KEY (`ID`),
  KEY `auction_id_image_foreign` (`auction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `auction_status`
--

DROP TABLE IF EXISTS `auction_status`;
CREATE TABLE IF NOT EXISTS `auction_status` (
  `ID` tinyint UNSIGNED NOT NULL AUTO_INCREMENT,
  `status` enum('Active','Paused','Ended','Cancelled') NOT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `auction_tag_info`
--

DROP TABLE IF EXISTS `auction_tag_info`;
CREATE TABLE IF NOT EXISTS `auction_tag_info` (
  `auction_id` bigint UNSIGNED NOT NULL,
  `tag_id` bigint UNSIGNED NOT NULL,
  PRIMARY KEY (`auction_id`,`tag_id`),
  KEY `tag_id_info_foreign` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `auction_type`
--

DROP TABLE IF EXISTS `auction_type`;
CREATE TABLE IF NOT EXISTS `auction_type` (
  `ID` tinyint UNSIGNED NOT NULL AUTO_INCREMENT,
  `type` enum('price up','blind','dutch') NOT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `bids`
--

DROP TABLE IF EXISTS `bids`;
CREATE TABLE IF NOT EXISTS `bids` (
  `bid_id` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  `auction_id` bigint UNSIGNED NOT NULL,
  `user_id` bigint UNSIGNED NOT NULL,
  `bid_amount` float UNSIGNED NOT NULL,
  `bid_time` datetime NOT NULL,
  PRIMARY KEY (`bid_id`),
  KEY `user_id_foreign` (`user_id`),
  KEY `auction_id_foreign` (`auction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `item_status`
--

DROP TABLE IF EXISTS `item_status`;
CREATE TABLE IF NOT EXISTS `item_status` (
  `ID` tinyint UNSIGNED NOT NULL AUTO_INCREMENT,
  `item_condition` enum('Brand New','Barely Used','Used','Damaged') NOT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `roles`
--

DROP TABLE IF EXISTS `roles`;
CREATE TABLE IF NOT EXISTS `roles` (
  `ID` tinyint UNSIGNED NOT NULL AUTO_INCREMENT,
  `roles` enum('Admin','Buyer','Seller') NOT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `tags`
--

DROP TABLE IF EXISTS `tags`;
CREATE TABLE IF NOT EXISTS `tags` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  `tag_name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `user`
--

DROP TABLE IF EXISTS `user`;
CREATE TABLE IF NOT EXISTS `user` (
  `ID` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  `email` varchar(255) NOT NULL,
  `username` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `role_id` tinyint UNSIGNED NOT NULL,
  `date_created` date NOT NULL,
  `status_id` tinyint UNSIGNED NOT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `email` (`email`),
  UNIQUE KEY `username` (`username`),
  KEY `user_status_id_foreign` (`status_id`),
  KEY `user_role_id_foreign` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- --------------------------------------------------------

--
-- Table structure for table `user_status`
--

DROP TABLE IF EXISTS `user_status`;
CREATE TABLE IF NOT EXISTS `user_status` (
  `ID` tinyint UNSIGNED NOT NULL AUTO_INCREMENT,
  `status` enum('Active','Suspended') NOT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

--
-- Constraints for dumped tables
--

--
-- Constraints for table `auction`
--
ALTER TABLE `auction`
  ADD CONSTRAINT `auction_status_foreign` FOREIGN KEY (`status_id`) REFERENCES `auction_status` (`ID`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `auction_type_foreign` FOREIGN KEY (`auction_type`) REFERENCES `auction_type` (`ID`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `seller_id_foreign` FOREIGN KEY (`seller_id`) REFERENCES `user` (`ID`) ON DELETE RESTRICT ON UPDATE RESTRICT;

--
-- Constraints for table `auction_details`
--
ALTER TABLE `auction_details`
  ADD CONSTRAINT `auction_id_details` FOREIGN KEY (`ID`) REFERENCES `auction` (`auction_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

--
-- Constraints for table `auction_images`
--
ALTER TABLE `auction_images`
  ADD CONSTRAINT `auction_id_image_foreign` FOREIGN KEY (`auction_id`) REFERENCES `auction` (`auction_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

--
-- Constraints for table `auction_tag_info`
--
ALTER TABLE `auction_tag_info`
  ADD CONSTRAINT `auction_id_info_foreign` FOREIGN KEY (`auction_id`) REFERENCES `auction` (`auction_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `tag_id_info_foreign` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

--
-- Constraints for table `bids`
--
ALTER TABLE `bids`
  ADD CONSTRAINT `auction_id_foreign` FOREIGN KEY (`auction_id`) REFERENCES `auction` (`auction_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `user_id_foreign` FOREIGN KEY (`user_id`) REFERENCES `user` (`ID`) ON DELETE RESTRICT ON UPDATE RESTRICT;

--
-- Constraints for table `user`
--
ALTER TABLE `user`
  ADD CONSTRAINT `user_role_id_foreign` FOREIGN KEY (`role_id`) REFERENCES `roles` (`ID`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `user_status_id_foreign` FOREIGN KEY (`status_id`) REFERENCES `user_status` (`ID`);
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
