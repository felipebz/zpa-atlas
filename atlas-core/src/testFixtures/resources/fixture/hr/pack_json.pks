create or replace package pack_json is
  type json_list is table of json;
  function make(p in json) return json;
  procedure use_it;
  procedure f(p json);
  procedure f(p varchar2);
end;
/
