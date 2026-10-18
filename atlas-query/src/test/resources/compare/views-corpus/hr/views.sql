create or replace view v_same as
  -- leading comment, not stored
  select t.*, 'x' tag
    from t_a t
/
create or replace view v_diff as
  select id
    from t_b
/
