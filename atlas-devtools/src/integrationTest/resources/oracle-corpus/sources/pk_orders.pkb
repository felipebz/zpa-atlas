create or replace package body pk_orders is
  procedure add_order(p_customer number) is
  begin
    add_order(p_customer, 0);
  end;

  procedure add_order(p_customer number, p_amount number) is
    r t_order%rowtype;
  begin
    r.id := seq_order.nextval;
    r.customer_id := p_customer;
    r.amount := nvl(p_amount, 0);
    insert into t_order values r;
    dbms_output.put_line(pk_customers.find_name(p_customer));
  end;

  procedure price(p_value ty_money) is
  begin
    if p_value.amount < 0 then
      raise_application_error(-20001, 'negative');
    end if;
  end;

  procedure report is
    n number;
  begin
    if g_ids.exists(1) then
      null;
    end if;
    select count(*) into n from v_open_orders;
    execute immediate 'delete from t_audit';
    pk_customers.deactivate(1);
  end;
end pk_orders;
/
