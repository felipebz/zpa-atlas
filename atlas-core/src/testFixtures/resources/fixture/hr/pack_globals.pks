create or replace package pack_globals is
  g_shared number;
  procedure run(p_id in number);
  procedure fill(p_out out number);
  procedure hide;
end pack_globals;
/
