create or replace package body pk_doc is
  procedure save(comentario comentario.texto%type) is
  begin
    null;
  end;

  function company(p number) return number is
  begin
    return p;
  end;
end;
/
