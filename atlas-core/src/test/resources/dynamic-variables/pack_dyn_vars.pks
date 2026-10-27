create or replace package pack_dyn_vars is
  g_spec_sql varchar2(200) := 'delete from event_log';
  procedure fill_text(p_text out varchar2);
  procedure keep_text(p_text in varchar2);
  procedure run_all(p_sql in varchar2);
end pack_dyn_vars;
/
