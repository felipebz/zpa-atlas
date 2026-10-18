create or replace package body pack_dynamic is
  procedure log_step(p_msg in varchar2) is begin null; end;

  procedure run(p_id in number, p_table in varchar2, p_cond in varchar2) is
    v_name varchar2(100);
    v_more varchar2(100);
    c sys_refcursor;
    v_cursor integer;
  begin
    execute immediate 'delete from company where id = 1';
    execute immediate 'select company_name from company where id = 1' into v_name;
    execute immediate 'with q as (select id, company_name from company) select company_name from q where id = 2' into v_name;
    execute immediate 'lock table company in exclusive mode';
    execute immediate 'truncate table company';
    execute immediate 'alter session set nls_date_format = ''YYYY-MM-DD''';
    execute immediate 'delete from company where id = ' || p_id;
    execute immediate 'delete from ' || p_table;
    execute immediate 'update company set st_active = ''N'' where ' || p_cond;
    execute immediate 'select x, a.y from ' || p_table || ' a where a.id = 1' into v_name;
    execute immediate 'select id from company where id = 1 ' || v_more into v_name;
    execute immediate 'this is not sql ' || p_table;
    execute immediate 'begin pack_process.get_event(1, :o, :io); end;' using out v_name, in out v_more;
    execute immediate 'begin log_step(''x''); end;';
    execute immediate 'declare v number; begin v := 1; end;';
    execute immediate 'begin log_step(p_table); end;';
    execute immediate 'update company
      set company_name = ''x'' where id = 3';
    open c for 'select id from event_log where msg = ''y''';
    v_cursor := dbms_sql.open_cursor;
    dbms_sql.parse(v_cursor, 'delete from event_log where id = 4', dbms_sql.native);
    execute immediate v_name;
  end;
end pack_dynamic;
/
