create or replace procedure p_ext_objects is
  v_doc xmltype;
  v_trace varchar2(4000);
begin
  dbms_output.put_line('x');
  v_doc := xmltype('<a/>');
  v_trace := dbms_utility.format_error_backtrace;
end;
/
