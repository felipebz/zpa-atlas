create or replace trigger trg_dynamic
before update on company
for each row
begin
  execute immediate 'update company set st_active = :new.st_active where id = 5';
end;
/
