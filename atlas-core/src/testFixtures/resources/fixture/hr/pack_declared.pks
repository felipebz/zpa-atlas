create or replace package pack_declared is
  -- a constant whose value spans lines, with comments inside
  c_label constant varchar2(40) :=
    'ledger' -- the prefix
    || '_' || /* the separator */ 'note';
  g_stamp date := sysdate + 1;
  g_amount send_queue.id%type;
  -- a value longer than the index keeps
  c_notice constant varchar2(400) := 'Every ledger entry posted after the monthly close is held for review by the controller, '
    || 'and an entry held for more than five working days is returned to its author with the reason written in the review note, '
    || 'unless the controller extends the hold.';
  g_mode $IF $$declared_wide $THEN varchar2(20) $ELSE number $END;
  type r_line is record (qty number default 1, label varchar2(10) not null := 'x');
  cursor c_lines return r_line;
  procedure post(p_id in number, p_note in varchar2 default 'none', p_when in date default sysdate);
  function total(p_id in number) return number;
  function ratio return number;
$IF $$declared_legacy $THEN
  procedure post_new(p_id in number);
$ELSE
  procedure post_old(p_id in number);
$END
end pack_declared;
/
