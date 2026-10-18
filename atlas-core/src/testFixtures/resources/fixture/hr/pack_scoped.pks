create or replace package pack_scoped as
  procedure check_items(o out number);
  procedure recheck;
  procedure pub(p number);
end;
/
