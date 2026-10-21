create or replace package body pack_imp_a is
  procedure leaf(p_n number) is
  begin
    if p_n > 0 then
      leaf(p_n - 1);
    end if;
  end;

  procedure mid(p_n number) is
  begin
    leaf(p_n);
  end;

  procedure ping(p_n number) is
  begin
    pong(p_n);
  end;

  procedure pong(p_n number) is
  begin
    ping(p_n - 1);
    leaf(p_n);
  end;

  procedure ov(p number) is
  begin
    leaf(p);
  end;

  procedure ov(p varchar2) is
  begin
    null;
  end;
end pack_imp_a;
/
