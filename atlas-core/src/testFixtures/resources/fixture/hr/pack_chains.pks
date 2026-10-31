create or replace package pack_chains is
  procedure run;
  procedure take(p_in in number);
  procedure put(p_out out number);
end pack_chains;
/
