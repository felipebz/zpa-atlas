create or replace procedure p_dictionary is
  r user_tables%rowtype;
  v_column user_tab_columns.column_name%type;
  n number;
begin
  select count(*) into n from user_tables t, user_tab_columns c where t.table_name = c.table_name and c.column_name = v_column;
end;
/
