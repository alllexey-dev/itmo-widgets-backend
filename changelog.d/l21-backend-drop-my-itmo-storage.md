# Database

- Migration `V12__drop_my_itmo_storage.sql` drops `my_itmo_storage`, the
  MyITMO token table that Backend has not read or written since V8
  (`service_credentials` holds the tokens). A rollback to 1.8.0 or 1.7.0 stays
  image-only; a rollback to 1.2.1 or older now needs the pre-deployment dump.
