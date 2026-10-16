create or replace function fn_company_name(p_id in number) return company.company_name%type is
  v_name company.company_name%type;
begin
  select company_name into v_name from company where id = p_id;
  return v_name;
end fn_company_name;
/
