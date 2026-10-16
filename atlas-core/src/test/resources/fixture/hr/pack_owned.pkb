create or replace package body "HR"."PACK_OWNED" is
  procedure p(x number) is
  begin
    null;
  end;

  procedure use_all is
  begin
    p(1);
    pack_owned.p(1);
    hr.pack_owned.p(1);
    proc_owned(1);
  end;
end;
/
