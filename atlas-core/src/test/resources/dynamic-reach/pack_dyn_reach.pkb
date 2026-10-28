create or replace package body pack_dyn_reach is
  procedure fill_text(p_text out varchar2) is begin p_text := 'delete from company'; end;

  procedure run_read(p_flag in varchar2, p_other in varchar2) is
    v_build varchar2(400);
    v_opt varchar2(400);
    v_two varchar2(400);
    v_reuse varchar2(400);
    v_wrapped varchar2(400);
    v_branch varchar2(400) := 'delete from event_log';
    v_loop varchar2(400) := 'select id from event_log';
    v_shared varchar2(400);
    rc sys_refcursor;
  begin
    v_build := ' from company';
    v_build := 'select company_name' || v_build;
    v_build := v_build || ' where id = 1';
    execute immediate v_build;
    v_build := 'select company_name from company';
    v_build := v_build || ' where id = 2';
    execute immediate v_build;
    v_opt := 'select company_name from company where 1 = 1';
    if p_flag = 'A' then
      v_opt := v_opt || ' and id = 3';
    end if;
    execute immediate v_opt;
    v_two := 'select msg from event_log where 1 = 1';
    if p_flag = 'B' then
      v_two := v_two || ' and id = 4';
    end if;
    if p_other = 'C' then
      case p_flag
        when 'X' then v_two := v_two || ' and msg = ''x''';
        else null;
      end case;
    end if;
    execute immediate v_two;
    v_reuse := 'delete from send_queue where id = 5';
    execute immediate v_reuse;
    v_reuse := 'delete from event_log where id = 5';
    execute immediate v_reuse;
    v_wrapped := 'update company set st_active = 0';
    v_wrapped := v_wrapped || ' where id = 6';
    begin
      execute immediate v_wrapped;
    exception
      when others then null;
    end;
    v_branch := 'delete from company where id = 7';
    if p_flag = 'D' then
      execute immediate v_branch;
    end if;
    v_loop := 'select id from send_queue where id = 8';
    for i in 1 .. 3 loop
      execute immediate v_loop;
    end loop;
    v_shared := 'select payload from send_queue';
    v_shared := v_shared || ' where id = 9';
    execute immediate v_shared;
    open rc for v_shared;
  end;

  procedure run_unread(p_flag in varchar2) is
    v_elsif varchar2(400);
    v_nested varchar2(400);
    v_inner varchar2(400);
    v_into varchar2(400);
    v_call varchar2(400);
    v_loop varchar2(400);
    v_handler varchar2(400);
    v_else varchar2(400);
    v_middle varchar2(400);
    v_null varchar2(400);
  begin
    v_elsif := 'select id from company where 1 = 1';
    if p_flag = 'A' then
      v_elsif := v_elsif || ' and id = 1';
    elsif p_flag = 'B' then
      v_elsif := v_elsif || ' and id = 2';
    end if;
    execute immediate v_elsif;
    v_nested := 'select id from company';
    begin
      v_nested := v_nested || ' where id = 3';
    end;
    execute immediate v_nested;
    v_inner := 'select id from company';
    if p_flag = 'C' then
      v_inner := 'select id from send_queue';
    end if;
    execute immediate v_inner;
    v_into := 'select id from company';
    select payload into v_into from send_queue where id = 4;
    execute immediate v_into;
    v_call := 'select id from company';
    unknown_proc(v_call);
    execute immediate v_call;
    v_loop := 'select id from company';
    for i in 1 .. 3 loop
      v_loop := v_loop || ' where id = 5';
      execute immediate v_loop;
    end loop;
    v_handler := 'select id from company';
    begin
      v_handler := 'select id from event_log';
      null;
    exception
      when others then
        execute immediate v_handler;
    end;
    if p_flag = 'D' then
      v_else := 'delete from company';
    else
      v_else := 'delete from event_log';
    end if;
    execute immediate v_else;
    v_middle := 'select id from company';
    v_middle := 'select ' || v_middle || ' from dual';
    execute immediate v_middle;
    v_null := v_null || 'delete from send_queue';
    v_null := v_null || ' where id = 6';
    execute immediate v_null;
  end;

  procedure run_goto is
    v_goto varchar2(400);
  begin
    v_goto := 'delete from company';
    goto done;
    v_goto := v_goto || ' where id = 7';
    <<done>>
    execute immediate v_goto;
  end;

  function get_sql(p_text out varchar2) return boolean is begin p_text := 'delete from event_log'; return true; end;

  function get_num(p_text in out varchar2) return number is begin p_text := p_text || ' and 1 = 1'; return 1; end;

  procedure run_conditions(p_flag in varchar2) is
    v_if varchar2(400);
    v_elsif varchar2(400);
    v_decl varchar2(400);
    v_case varchar2(400);
    v_while varchar2(400);
    v_handled varchar2(400);
  begin
    v_if := 'delete from company where id = 1';
    if pack_dyn_reach.get_sql(v_if) then
      execute immediate v_if;
    end if;
    v_elsif := 'delete from company where id = 2';
    if p_flag = 'A' then
      null;
    elsif pack_dyn_reach.get_sql(v_elsif) then
      execute immediate v_elsif;
    end if;
    v_decl := 'delete from company where id = 3';
    declare
      b boolean := pack_dyn_reach.get_sql(v_decl);
    begin
      execute immediate v_decl;
    end;
    v_case := 'delete from company where id = 4';
    case pack_dyn_reach.get_num(v_case)
      when 1 then execute immediate v_case;
      else null;
    end case;
    v_while := 'delete from company where id = 5';
    while pack_dyn_reach.get_sql(v_while) loop
      execute immediate v_while;
    end loop;
    v_handled := 'delete from company where id = 6';
    begin
      execute immediate v_handled;
    exception
      when others then
        v_handled := 'delete from event_log where id = 6';
    end;
  end;
end pack_dyn_reach;
/
