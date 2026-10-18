create or replace view v_company_ioi as
  select id, company_name from company
/
create or replace trigger trg_v_company_ioi2
instead of insert on v_company_ioi
for each row
begin
  null;
end;
/
