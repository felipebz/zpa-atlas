create or replace view v_shape_obj of ty_shape with object identifier (id) as select id from company;
create or replace view v_circle_obj of ty_circle under v_shape_obj as select id, 1 r from company;
create or replace view v_obj_refs as select make_ref(v_shape_obj, id) sref from company;
create or replace procedure proc_object_view_ref is
  n number;
begin
  select count(*) into n from dual where make_ref(v_shape_obj, 1) is not null;
end;
/
create or replace procedure proc_table_ref is
  n number;
begin
  select count(*) into n from dual where make_ref(shape_tab, 1) is not null;
end;
/
