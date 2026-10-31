create or replace package pack_doc is
  -- Posts ledger entries.
  --   One entry per account and day.
  procedure post_entry(p_account in number, -- the account to post to
                       p_amount in number); -- the amount, in cents

  --%suite(ledger)
  procedure only_annotated;

  -----------------------------------------
  procedure only_separators;

  -- Copyright 2024 Example Ledger Ltd. All rights reserved.
  procedure licensed;

  -- l_total := l_total + 1;
  -- commit;
  procedure commented_code;

  -- One blank line away from its procedure.

  procedure gapped;

  g_rate number; -- the rate, in percent
  g_limit number; -- the limit
  g_next number;

  -----------------------------------------
  -- Closes the period.
  --%deprecated
  -----------------------------------------
  procedure close_period;

  /** Spec and body say the same. */
  procedure same_both;

  /** The spec says this. */
  procedure differ_both;

  procedure body_only;

  /**
   * Only the spec says this.
   *   Indented under a star.
   */
  procedure spec_only;

  type r_entry is record (
    id number,     -- the entry id
    amount number  -- the entry amount
  );
end pack_doc;
/
