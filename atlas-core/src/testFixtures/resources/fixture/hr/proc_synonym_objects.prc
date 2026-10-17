create or replace procedure proc_synonym_objects is
  v_doc xmltype;
  v_trace varchar2(4000);
  n number;
begin
  dbms_output.put_line('x');
  v_trace := dbms_utility.format_error_backtrace;
  v_doc := xmltype('<a/>');
  n := get_env('X');
  select syn_seq.nextval into n from dual;
  syn_ext_pkg.run(1);
  n := syn_calls.f_pub(1);
  select count(*) into n from dbms_utility;
  select xmlagg(xmlelement("a", 1) order by 1) into v_doc from dual;
end proc_synonym_objects;
/
create or replace function concat_agg(p varchar2) return varchar2 aggregate using ty_point;
/
