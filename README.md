# AuctionHub - Online Auction Platform

**CSIT-26-S2-12** | Product Version 0.0

A C2C online auction platform built with JSP + Servlet + JavaBean following the MVC three-tier architecture.

## Tech Stack

- **Backend**: Java 11, Servlet 4.0, JSP, JSTL
- **Frontend**: Bootstrap 5, HTML5, CSS3, JavaScript
- **Database**: MySQL 8
- **Build Tool**: Maven
- **Server**: Apache Tomcat 9+

## Prerequisites

- JDK 11+
- Apache Maven 3.6+
- Apache Tomcat 9 or 10
- MySQL 8.0+

## Setup Instructions

### 1. Database Setup

```bash
mysql -u root -p < src/main/resources/db.sql
```

### 2. Configure Database Connection

Edit `src/main/java/com/auction/util/DBUtil.java` and update:
- `URL` - your MySQL connection URL
- `USER` - your MySQL username
- `PASSWORD` - your MySQL password

### 3. Build

```bash
mvn clean package
```

### 4. Deploy

Copy the generated `target/online-auction.war` to your Tomcat `webapps/` directory, or deploy directly from your IDE.

### 5. Access

Open `http://localhost:8080/online-auction/` in your browser.

## Default Admin Account

- Username: `admin`
- Password: `admin123`

## Project Structure

```
src/main/java/com/auction/
├── model/      # JavaBean data models
├── dao/        # Data Access Objects (JDBC)
├── servlet/    # Servlet controllers
├── filter/     # Request filters
└── util/       # Utility classes

src/main/webapp/
├── common/     # Shared JSP components
├── auth/       # Login, Register, Profile pages
├── product/    # Product listing, detail, creation
├── auction/    # Bid-related pages
├── admin/      # Admin dashboard and management
├── css/        # Custom stylesheets
├── js/         # Custom JavaScript
└── index.jsp   # Homepage
```

## Features (v0.0)

- [x] User registration and authentication (Buyer/Seller/Admin roles)
- [x] Product listing and search
- [x] Auction creation with configurable strategies (Price Up, Low Start High, Public Bidding)
- [x] Real-time bidding with countdown timers
- [x] Admin dashboard with user management
- [x] Responsive UI with Bootstrap 5

## Team

CSIT-26-S2-12 | Session S2'26 & S3'26
