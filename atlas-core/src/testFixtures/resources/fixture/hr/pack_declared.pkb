create or replace package body pack_declared is
  cursor c_lines return r_line is select 1, 'a' from dual;
  -- p_note leaves out the spec's default; p_when's differs from the spec's (Oracle refuses that, zpa still pairs them)
  procedure post(p_id in number, p_note in varchar2, p_when in date default trunc(sysdate)) is
  begin
    null;
  end;
  function total(p_id in number) return number is begin return p_id; end;
  function ratio return integer is begin return 1; end;
$IF $$declared_legacy $THEN
  procedure post_new(p_id in number) is begin null; end;
$ELSE
  procedure post_old(p_id in number) is begin null; end;
$END
end pack_declared;
/
