-- Runs once on first container start (POSTGRES_DB=orders already exists).
-- Create the second logical database used by the downstream inventory-consumer.
CREATE DATABASE fulfillment;
