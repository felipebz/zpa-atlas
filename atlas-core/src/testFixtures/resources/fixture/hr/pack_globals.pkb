create or replace package body pack_globals is
  -- the running total of the session
  g_total number := 0;
  c_limit constant number := 100; -- the most one run takes
  -- redeclares the spec's g_shared: Oracle compiles it, and fails where the name is used
  g_shared varchar2(10);
  type r_pair is record (a number, b number);
  g_pair r_pair;
  type t_ids is table of number index by pls_integer;
  g_ids t_ids;
  g_label varchar2(30);
  e_full exception;

  procedure fill(p_out out number) is begin p_out := 1; end;

  procedure run(p_id in number) is
    l_count number;
  begin
    l_count := g_total;
    g_total := g_total + c_limit;
    select count(*) into g_total from send_queue;
    fill(g_total);
    g_ids(1) := l_count;
    l_count := g_ids(1);
    g_pair.a := 1;
    l_count := g_ids.count;
    pack_globals.g_total := 2;
    l_count := pack_globals.c_limit;
    select count(*) into l_count from company where id = c_limit;
    select count(*) into l_count from missing_table where x = g_label;
    g_shared := 'x';
  end;

  procedure hide is
    g_total number;
  begin
    g_total := 3;
  end;
end pack_globals;
/
