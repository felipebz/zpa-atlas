create or replace package pack_calls is
  type t_nums is table of number;
  g_tab t_nums;
  function f_pub(p_x number) return number;
  procedure p_pub(p number);
  procedure p_pub(p varchar2);
  procedure p_mix(p_a number);
  procedure p_mix(p_a number, p_b varchar2);
end pack_calls;
/
