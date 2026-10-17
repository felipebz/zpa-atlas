create or replace package body dbms_random is
  function value(low number, high number) return number is
  begin
    return low;
  end;
end;
/
