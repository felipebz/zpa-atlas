create or replace package body pack_scoped as
  type t_n is table of number index by binary_integer;
  g_items t_n;
  procedure pub(p number) is begin null; end;
  procedure check_items(o out number) is
    l_items t_n;
    procedure add_entry(p number) is begin l_items(p) := p; end;
  begin
    add_entry(1);
    check_items.add_entry(2);
    pack_scoped.check_items.add_entry(3);
    hr.pack_scoped.check_items.add_entry(4);
    check_items.l_items.delete;
    o := check_items.l_items.count;
  end;
  procedure recheck is
    procedure add_reason(p number) is begin null; end;
  begin
    pack_scoped.recheck.add_reason(1);
    pack_scoped.g_items.delete;
    pack_scoped.g_items(1) := 1;
    if pack_scoped.g_items.exists(1) then null; end if;
    pack_scoped.pub(1);
    pack_other.g_items.delete;
  end;
end;
/
