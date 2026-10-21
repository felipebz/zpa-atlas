create or replace package pack_imp_a is
  procedure leaf(p_n number);
  procedure mid(p_n number);
  procedure ping(p_n number);
  procedure pong(p_n number);
  procedure ov(p number);
  procedure ov(p varchar2);
end pack_imp_a;
/
