create or replace package pk_g is
  procedure run(p_v number);
end pk_g;
/
create or replace package body pk_g is
  procedure run(p_v number) is
  begin
    $if pk_flags.c_on $then
      dbms_output.put_line(p_v);
    $end
    null;
  end;
end pk_g;
/
create or replace package pk_d is
  procedure binds(p_a out number, p_b in number, p_c in out number, p_d number);
  procedure drain(p_cursor sys_refcursor);
end pk_d;
/
create or replace package body pk_d is
  g_a number;
  g_cur sys_refcursor;
  procedure binds(p_a out number, p_b in number, p_c in out number, p_d number) is
  begin
    execute immediate 'begin :1 := 1; end;' using out p_a;
    execute immediate 'begin :1 := 1; end;' using out g_a;
    execute immediate 'begin :1 := :1 + 1; end;' using in out p_c;
    execute immediate 'select :1 from dual' using in p_b;
    execute immediate 'select :1 from dual' using p_d;
    open g_cur for 'select :1 from dual' using p_b;
  end;
  procedure drain(p_cursor sys_refcursor) is
    l_n number;
  begin
    loop
      fetch p_cursor into l_n;
      exit when p_cursor%notfound;
    end loop;
    if g_cur%isopen then close g_cur; end if;
  end;
end pk_d;
/
