create or replace type json as object (
  n number,
  constructor function json return self as result,
  member function size_of return number
)
/
