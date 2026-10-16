create or replace package pk_orders is
  type t_ids is table of number;
  g_ids t_ids := t_ids();
  procedure add_order(p_customer number);
  procedure add_order(p_customer number, p_amount number);
  procedure price(p_value ty_money);
  procedure report;
end pk_orders;
/
