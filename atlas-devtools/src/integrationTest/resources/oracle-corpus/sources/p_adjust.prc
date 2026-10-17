create or replace procedure p_adjust(p_in number, p_out out number, p_io in out varchar2) is
begin
  p_out := p_in;
  p_io := p_io;
end;
/
