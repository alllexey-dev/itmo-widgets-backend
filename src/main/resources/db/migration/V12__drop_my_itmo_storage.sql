-- The MyITMO tokens live in service_credentials since V8; V8 kept this table only for the image-only rollback
-- to 1.2.1, whose window has closed. No foreign key references it, and 1.7.0 and 1.8.0 never read it, so a
-- rollback to them stays image-only.
DROP TABLE my_itmo_storage;
