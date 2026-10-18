create or replace package pack_args is
  procedure p(p_code in number, p_name in varchar2 default null, p_flag in boolean default null);
  procedure caller(p_in in number);
end pack_args;
/
