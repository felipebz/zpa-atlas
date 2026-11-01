create or replace procedure use_gauge(o ty_gauge, d in out ty_dial) is
  n number;
begin
  n := o.a;
  d.b := 'x';
  n := d.c.x;
end;
/
