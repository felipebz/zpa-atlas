create or replace package pack_dynamic is
  procedure run(p_id in number, p_table in varchar2, p_cond in varchar2);
end pack_dynamic;
/
