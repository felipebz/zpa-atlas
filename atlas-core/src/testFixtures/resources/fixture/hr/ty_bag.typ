create or replace type ty_bag as object (
  entries ty_num_list,
  member function g(n number) return number,
  member function nth(i number) return number
)
/
