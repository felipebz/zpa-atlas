create or replace procedure proc_xmlseq(p_doc xmltype) is
  n number;
begin
  select count(*) into n from table(xmlsequence(extract(p_doc, '/a/b')));
end proc_xmlseq;
/
