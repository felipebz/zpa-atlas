create or replace package body pack_doc is

  /*
    Purpose:  Ledger posting.

    Who     Date        Description
    ------  ----------  -----------
    ABC     01.02.2024  Created
  */

  g_private number;

  procedure post_entry(p_account in number, p_amount in number) is
  begin
    null;
  end;

  procedure only_annotated is begin null; end;
  procedure only_separators is begin null; end;
  procedure licensed is begin null; end;
  procedure commented_code is begin null; end;
  procedure gapped is begin null; end;
  procedure close_period is begin null; end;

  procedure same_both is
  begin
    /* Spec and body say the same. */

    null;
  end;

  procedure differ_both is
    l_count number;
  begin
    /*
      The body says that.
    */

    null;
  end;

  procedure body_only
  is
    -- Only the body says this,
    --   indented on its second line.

    l_x number;
  begin
    null;
  end;

  procedure spec_only is begin null; end;

  procedure glued_begin is
  begin
    -- sets the counter up
    null;
  end;
end pack_doc;
/
