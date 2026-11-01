create or replace type ty_peg as object (tag varchar2(10));
/
create or replace type ty_peg_list as table of ty_peg;
/
create or replace type ty_int_list as table of number;
/
