create or replace type ty_tray as object (
  items ty_int_list,
  slots ty_peg_list,
  constructor function ty_tray return self as result,
  member procedure fill,
  member procedure fill_x(self in out nocopy ty_tray),
  member function first_tag return varchar2
);
/
