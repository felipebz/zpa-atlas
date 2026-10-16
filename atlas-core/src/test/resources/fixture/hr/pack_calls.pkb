create or replace package body pack_calls is
  procedure p_priv(p_x number, p_y varchar2 default null) is
  begin
    null;
  end;

  procedure p_over(p_x number) is begin null; end;
  procedure p_over(p_x varchar2, p_y number) is begin null; end;

  procedure p_mix(p_a number, p_b varchar2, p_c date) is begin null; end;

  procedure p_pub(p number) is begin null; end;
  procedure p_pub(p varchar2) is begin null; end;

  function f_pub(p_x number) return number is
  begin
    return p_x;
  end;

  procedure p_mix(p_a number) is
    v t_nums := t_nums();
    o ty_point := ty_point(1);
    n number;
    procedure local_p(p number) is begin null; end;
  begin
    local_p(1);
    v.extend;
    n := v.count();
    if v.exists(1) then null; end if;
    n := g_tab(1);
    n := pack_calls.g_tab(1);
    pack_process.run_job(1, 'a', 2, sysdate, 3);
    pack_process.run_job(1, 'a', 2, sysdate);
    pack_process.run_job(p_a => 1, p_b => 'a', p_c => 2, p_d => sysdate);
    pack_process.run_job(1, 'a', 2, sysdate, p_e => 3);
    p_pub(1);
    p_priv(1);
    pack_calls.p_priv(1, p_y => 'y');
    p_over(1);
    p_mix(1, 'b');
    proc_standalone(1);
    proc_standalone;
    hr.proc_standalone(p_id => 2);
    dbms_output.put_line(nvl(substr('x', 1, 1), 'y'));
    select f_pub(1) into n from dual;
  end;
end pack_calls;
/
