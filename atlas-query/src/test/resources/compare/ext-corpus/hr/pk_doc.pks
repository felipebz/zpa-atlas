create or replace package pk_doc is
  procedure save(comentario comentario.texto%type);
  function company(p number) return number;
end;
/
