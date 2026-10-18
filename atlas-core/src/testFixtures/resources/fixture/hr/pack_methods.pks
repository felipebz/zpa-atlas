create or replace package pack_methods is
  type line_rec is record (line_no number);
  type t_line_tab is table of line_rec index by pls_integer;
  type t_doc is record (line_rec t_line_tab, items t_line_tab);
  type t_doc_tab is table of t_doc index by pls_integer;
  procedure run(i_doc in t_doc_tab);
  function f_x(p number) return varchar2;
end;
/
