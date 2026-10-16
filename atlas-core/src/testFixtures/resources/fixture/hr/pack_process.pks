create or replace package pack_process is
  type r_event is record (id number, msg varchar2(100));
  c_kind constant varchar2(1) := 'J';
  g_counter number;
  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date);
  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date, p_e in number default 0);
end pack_process;
/
