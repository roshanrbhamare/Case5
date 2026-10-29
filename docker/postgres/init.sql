-- Runs once, on the first start of the Postgres container.
-- The main database (rostersync) comes from POSTGRES_DB; Flyway creates its tables at backend startup.
-- This adds the separate database the integration tests use.
CREATE DATABASE rostersync_test;
