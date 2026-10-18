create or replace package body pack_args is
  procedure p(p_code in number, p_name in varchar2 default null, p_flag in boolean default null) is begin null; end;

  function f(p_x in number) return number is begin return p_x; end;

  procedure caller(p_in in number) is
    type t_rec is record (code number);
    r t_rec;
    v_local number := 3;
    v number;
  begin
    p(1234);
    p(-1);
    p(1.50);
    p(+1.5);
    p(1e3);
    p(p_code => 7, p_name => 'abc');
    p(1, 'it''s');
    p(1, q'[it's]');
    p(1, n'x');
    p(1, null);
    p(1, p_flag => true);
    p((5));
    p(v_local);
    p(p_in);
    p(1, pack_process.c_kind);
    p(r.code);
    p(1 + 1);
    p(1, 'a' || 'b');
    p(f(1));
    p(1, to_char(sysdate));
    select nvl(max(id), 0) into v from company;
    execute immediate 'begin pack_args.p(:1, ''z''); end;';
    p(- -1);
    p(1, '');
  end;
end pack_args;
/
