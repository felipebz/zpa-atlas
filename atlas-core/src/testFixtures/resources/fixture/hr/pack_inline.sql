create or replace package pack_inline is
  e_inline exception;
  cursor c_inline(p_id number) is select id from company where id = p_id;
  procedure go;
end;
/
create or replace package body pack_inline is
  cursor c_pending is select 1 x from dual;
  e_failed exception;
  procedure go is
    v c_inline%rowtype;
  begin
    open c_inline(1);
    fetch c_inline into v;
    if c_inline%found then null; end if;
    close c_inline;
    for r in c_inline(p_id => 2) loop null; end loop;
    open c_pending; close c_pending;
    if pack_inline.c_pending%isopen then raise pack_inline.e_failed; end if;
    raise e_inline;
  exception
    when e_inline or no_data_found then raise e_failed;
    when e_failed then raise pack_inline.e_inline;
  end;
end;
/
