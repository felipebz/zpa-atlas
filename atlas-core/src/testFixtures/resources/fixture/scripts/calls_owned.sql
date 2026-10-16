declare
  n number;
  o ty_owned;
begin
  proc_owned(1);
  hr.proc_owned(1);
  pack_owned.p(1);
  hr.pack_owned.p(1);
  n := pack_owned.g_tab(1);
  o := ty_owned(1);
  other.proc_external(1);
  other.does_not_exist(1);
  proc_external(1);
  pack_owned.does_not_exist(1);
end;
/
