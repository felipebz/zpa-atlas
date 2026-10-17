create or replace trigger trg_order_columns
before update of amount, status on t_order
referencing new as n old as o
for each row
when (n.amount > 0)
begin
  :n.customer_id := :o.customer_id;
  select max(id) into :n.id from t_order where id = :o.id;
  p_adjust(:o.amount, :n.amount, :n.status);
  insert into t_audit (id, msg) values (:n.id, :o.status);
end;
/
create or replace trigger trg_order_compound
for update on t_order
compound trigger
  before each row is
  begin
    :new.amount := :old.amount;
  end before each row;
end trg_order_compound;
/
create or replace trigger trg_open_orders_ioi
instead of insert on v_open_orders
for each row
begin
  insert into t_order (id, customer_id, amount, status) values (:new.id, :new.customer_id, :new.amount, 'O');
end;
/
