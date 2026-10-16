create or replace function fat(n number) return number is
begin
  if n <= 1 then
    return 1;
  end if;
  return n * fat(n - 1);
end;
/
create or replace function area(x number) return number is
begin
  return x * x;
end;
/
create or replace trigger trg_area_check
before insert on company
for each row
declare
  function area(x number) return number is
  begin
    return x;
  end;
begin
  if area(1) > 0 then
    null;
  end if;
end;
/
create or replace type ty_square as object (
  side number,
  member function area return number,
  member function twice return number
);
/
create or replace type body ty_square as
  member function area return number is
  begin
    return side * side;
  end;
  member function twice return number is
  begin
    -- A SELF call (self.area): the legacy table sees a member function and calls it local; #437 will resolve it.
    return 2 * area();
  end;
end;
/
