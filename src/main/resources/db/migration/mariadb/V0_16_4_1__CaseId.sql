ALTER TABLE request ADD COLUMN case_id varchar(64) not null default '';
UPDATE request SET case_id = '';
