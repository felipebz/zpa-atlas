create or replace package body pack_imp_b is
  procedure helper is
  begin
    pack_imp_a.mid(1);
  end;

  procedure top is
  begin
    helper;
    pack_imp_a.mid(2);
  end;

  procedure curs_user is
    cursor c_imp is select imp_fn(1) as n from dual;
  begin
    for r in c_imp loop
      null;
    end loop;
  end;
begin
  pack_imp_a.leaf(0);
end pack_imp_b;
/
