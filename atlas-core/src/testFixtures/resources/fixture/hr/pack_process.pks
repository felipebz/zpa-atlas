create or replace package pack_process is
  type r_event is record (id number, msg varchar2(100)); type t_events is table of r_event index by pls_integer;
  c_kind constant varchar2(1) := 'J'; e_failed exception; cursor c_by_status(p_status varchar2) is select id from send_queue where record_type = p_status;
  g_counter number; g_rec r_event; g_events t_events; cursor c_pending is select id from send_queue;
  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date);
  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date, p_e in number default 0); function f_next return number; procedure get_event(p_id in number, p_out out number, p_io in out number);
end pack_process;
/
