create or replace procedure p_xmlseq is
  n number;
begin
  select count(*) into n from table(xmlsequence(extract(xmltype('<a><b/><b/></a>'), '/a/b')));
end;
/
