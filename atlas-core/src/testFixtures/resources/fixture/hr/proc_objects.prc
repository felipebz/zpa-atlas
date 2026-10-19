create or replace procedure proc_objects(p_list sys.odcinumberlist, p_doc xmltype) is
  r ref ty_shape;
  o ty_shape;
  n number;
begin
  select ref(x), value(x) into r, o from shape_tab x where x.id = 1;
  select count(*) into n from shape_tab s where value(s) is not null;
  select count(*) into n from shape_tab x where exists (select 1 from shape_holder h where h.s = ref(x));
  select ref(x) into r from shape_tab x, shape_holder h where h.id = x.id;
  select count(value(t)) into n from table(p_list) t;
  select count(*) into n from table(xmlsequence(extract(p_doc, '/a/b'))) t where extractvalue(value(t), '/b') is not null;
  select deref(h.s) into o from shape_holder h where h.id = 1;
  update shape_tab x set value(x) = ty_shape(2) where x.id = 2;
  select make_ref(shape_tab, 1) into r from dual;
  select make_ref(hr.shape_tab, 1) into r from dual;
  select make_ref(missing_obj_tab, 1) into r from dual;
end;
/
create or replace view v_shapes as select ref(x) sref, value(x) sval, x.id from shape_tab x;
create or replace view v_shape_refs as select make_ref(shape_tab, h.id) sref from shape_holder h;
