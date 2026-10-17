create or replace type ty_shape as object (id number) not final;
/
create or replace type ty_circle under ty_shape (r number, member function area return number);
/
create or replace type body ty_circle as
  member function area return number is
  begin
    return r * r;
  end;
end;
/
