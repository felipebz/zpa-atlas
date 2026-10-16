declare
  n number;
begin
  if pack_owned.g_tab.exists(1) then null; end if;
  if hr.pack_owned.g_tab.exists(1) then null; end if;
  if pack_calls.g_tab.exists(1) then null; end if;
  if pkg_ext.g_tab.exists(1) then null; end if;
  other.pack_x.do_it(1);
end;
/
