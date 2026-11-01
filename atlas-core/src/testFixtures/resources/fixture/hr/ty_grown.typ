create or replace type ty_grown as object (k number);
/
alter type ty_grown add attribute (m number) cascade;
/
