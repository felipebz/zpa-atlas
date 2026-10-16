create or replace package body pack_types is
  type t_rc is ref cursor return company%rowtype;

  procedure load(p_id company.id%type, p_row company%rowtype) is
    v_x number;
    v_copy v_x%type;
    cursor c_local is select id, company_name from company;
    v_local c_local%rowtype;
    v_nums pack_calls.t_nums;
    v_point ty_point;
    v_doc xmltype;
    v_payload hr.send_queue.payload%type;
    v_active pack_types.c_active%rowtype;
    v_view v_company%rowtype;
    v_missing company.no_such_column%type;
    v_rc t_rc;
  begin
    v_point := ty_point(1);
    v_nums := pack_calls.t_nums(1, 2);
    select cast(null as ty_point) into v_point from dual;
  end load;

  function name_of(p_id company.id%type) return s_company_name is
    v_name s_company_name;
  begin
    select company_name into v_name from company where id = p_id;
    return v_name;
  end name_of;
end pack_types;
/
