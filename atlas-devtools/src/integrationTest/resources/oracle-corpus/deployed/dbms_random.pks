create or replace package dbms_random is
  function value(low number, high number) return number;
end;
/
