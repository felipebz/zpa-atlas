create or replace package body pk_customers is
  function find_name(p_id number) return varchar2 is
    l_name t_customer.name%type;
  begin
    select name into l_name from syn_customer where id = p_id;
    return l_name;
  end;

  procedure deactivate(p_id number) is
  begin
    update t_customer set status = 'I' where id = p_id;
  end;
end pk_customers;
/
