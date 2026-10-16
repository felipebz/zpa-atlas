create or replace package body pack_nested is
  function md5_private(x varchar2) return varchar2 is
  begin
    return x;
  end;

  function wrapper(p varchar2) return varchar2 is
    function md5(x varchar2) return varchar2 is
    begin
      return x;
    end;
  begin
    return md5(p);
  end;

  function run(p varchar2) return varchar2 is
  begin
    return md5_private(wrapper(p));
  end;
end;
/
