create or replace package body pack_json is
  function make(p in json) return json is
  begin
    return p;
  end;

  procedure f(p json) is begin null; end;

  procedure f(p varchar2) is begin null; end;

  procedure use_it is
    l json;
    b json(object);
  begin
    l := make(json());
    f(l);
    l := json(1);
  end;
end;
/
