create or replace package imp_ovl is
  procedure base;
  procedure run(p number);
  procedure run(p varchar2);
end imp_ovl;
/
create or replace package body imp_ovl is
  procedure base is
  begin
    null;
  end;

  procedure run(p number) is
  begin
    base;
  end;

  procedure run(p varchar2) is
  begin
    base;
  end;
end imp_ovl;
/
