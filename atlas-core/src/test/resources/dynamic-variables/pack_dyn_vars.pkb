create or replace package body pack_dyn_vars is
  g_body_sql varchar2(200) := 'delete from send_queue';

  procedure fill_text(p_text out varchar2) is begin p_text := 'delete from company'; end;

  procedure keep_text(p_text in varchar2) is begin null; end;

  procedure run_all(p_sql in varchar2) is
    v_one varchar2(200);
    v_default varchar2(200) := 'update company set st_active = 1 where id = 2';
    c_fixed constant varchar2(200) := 'select company_name from company where id = 3';
    v_hidden varchar2(200) := 'delete from company';
    v_twice varchar2(200);
    v_grows varchar2(200) := 'select id from send_queue';
    v_queried varchar2(200);
    v_out varchar2(200);
    v_unseen varchar2(200) := 'select payload from send_queue';
    v_holes varchar2(200);
    n_count number := 1;
    v_cursor integer;
  begin
    v_one := 'insert into event_log (id, msg) values (1, ''x'')';
    pack_dyn_vars.keep_text(v_one);
    execute immediate v_one;
    execute immediate v_default;
    execute immediate c_fixed;
    declare
      v_hidden varchar2(200) := 'delete from event_log where id = 5';
    begin
      execute immediate v_hidden;
    end;
    v_twice := 'delete from company where id = 6';
    v_twice := 'delete from send_queue where id = 6';
    execute immediate v_twice;
    v_grows := v_grows || ' where id = 7';
    execute immediate v_grows;
    select payload into v_queried from send_queue where id = 8;
    execute immediate v_queried;
    pack_dyn_vars.fill_text(v_out);
    execute immediate v_out;
    unknown_proc(v_unseen);
    execute immediate v_unseen;
    v_holes := 'delete from company where id = ' || n_count;
    execute immediate v_holes;
    v_cursor := dbms_sql.open_cursor;
    dbms_sql.parse(v_cursor, v_default, dbms_sql.native);
    execute immediate g_body_sql;
    execute immediate g_spec_sql;
    execute immediate p_sql;
    execute immediate n_count;
  end;

  procedure run_shared is
    v_query varchar2(200) := 'select company_name from company where id = 9';
    rc sys_refcursor;
  begin
    execute immediate v_query;
    open rc for v_query;
  end;
end pack_dyn_vars;
/
