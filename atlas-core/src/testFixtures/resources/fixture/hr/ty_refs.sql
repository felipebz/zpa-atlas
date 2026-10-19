create or replace type ty_shape_link as object (target ref ty_shape, other ref hr.ty_shape);
/
create or replace type ty_shape_refs as table of ref ty_shape;
/
create or replace type ty_ring under ty_circle (inner_r number, member function ring_area return number);
/
create or replace type body ty_ring as
  member function ring_area return number is
  begin
    return (self as ty_circle).area() - inner_r * inner_r;
  end;
end;
/
create or replace package pack_refs is
  g_ref ref ty_shape;
  type r_ref is record (shape ref ty_shape, id number);
  type t_refs is table of ref ty_shape index by pls_integer;
  function first_ref(p_ref ref ty_shape) return ref ty_shape;
  procedure check_shape(p_shape ty_shape, p_ref ref ty_shape);
end;
/
create or replace package body pack_refs is
  function first_ref(p_ref ref ty_shape) return ref ty_shape is
  begin
    return p_ref;
  end;

  procedure check_shape(p_shape ty_shape, p_ref ref ty_shape) is
    v_circle ty_circle;
    v_ref ref ty_circle;
    v_far ref ty_shape@lnk;
    v_far_obj ty_shape@lnk;
    n number;
  begin
    v_circle := treat(p_shape as ty_circle);
    n := treat(p_shape as hr.ty_circle).r;
    v_ref := treat(p_ref as ref ty_circle);
    if p_shape is of (only ty_circle) then null; end if;
    select count(*) into n from company c
     where c.id = treat(p_shape as ty_circle).r
       and treat(p_ref as ref ty_circle) is not null
       and p_shape is of type (only ty_circle);
  end;
end;
/
