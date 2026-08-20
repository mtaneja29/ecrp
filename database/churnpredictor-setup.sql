-- ChurnPredictor one-time setup: project user + database + tables.
-- HOW TO RUN:
--   1. Open this file in MySQL Workbench using your ROOT connection
--   2. Change pick_a_password below to a password you choose
--   3. Click the lightning bolt to execute the whole script

CREATE USER IF NOT EXISTS 'churnpredictor'@'localhost' IDENTIFIED BY 'pick_a_password';

CREATE DATABASE IF NOT EXISTS churnpredictor;

GRANT ALL PRIVILEGES ON churnpredictor.* TO 'churnpredictor'@'localhost';

USE churnpredictor;

DROP TABLE IF EXISTS prediction_result;
DROP TABLE IF EXISTS customer;

CREATE TABLE customer (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id       VARCHAR(32)  NOT NULL,
    gender            VARCHAR(10),
    senior_citizen    TINYINT,
    partner           VARCHAR(5),
    dependents        VARCHAR(5),
    tenure            INT,
    phone_service     VARCHAR(5),
    multiple_lines    VARCHAR(20),
    internet_service  VARCHAR(20),
    online_security   VARCHAR(20),
    online_backup     VARCHAR(20),
    device_protection VARCHAR(20),
    tech_support      VARCHAR(20),
    streaming_tv      VARCHAR(20),
    streaming_movies  VARCHAR(20),
    contract          VARCHAR(20),
    paperless_billing VARCHAR(5),
    payment_method    VARCHAR(40),
    monthly_charges   DECIMAL(8,2),
    total_charges     DECIMAL(10,2)
);

CREATE TABLE prediction_result (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id       BIGINT NOT NULL,
    churn_probability DECIMAL(5,4),
    risk_band         VARCHAR(10),
    assessed_at       DATETIME,
    action_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    CONSTRAINT fk_prediction_customer
        FOREIGN KEY (customer_id) REFERENCES customer (id)
        ON DELETE CASCADE,

    -- dashboard filters by band then sorts by probability; the "All" tab sorts by probability alone
    INDEX idx_band_prob (risk_band, churn_probability),
    INDEX idx_prob (churn_probability)
);
