create or replace package pk_customers is
  function find_name(p_id number) return varchar2;
  procedure deactivate(p_id number);
end pk_customers;
/
