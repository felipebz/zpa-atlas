create or replace type ty_gauge as object (
  a number,
  b varchar2(20),
  member procedure bump
) not final;
/
