create or replace package pack_dyn_reach is
  procedure fill_text(p_text out varchar2);
  procedure run_read(p_flag in varchar2, p_other in varchar2);
  procedure run_unread(p_flag in varchar2);
  procedure run_goto;
  function get_sql(p_text out varchar2) return boolean;
  function get_num(p_text in out varchar2) return number;
  procedure run_conditions(p_flag in varchar2);
end pack_dyn_reach;
/
