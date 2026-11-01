create or replace type body ty_tray is
  constructor function ty_tray return self as result is
  begin
    self.items := ty_int_list();
    self.items.extend;                 -- line 5
    self.items(1) := 5;                -- line 6
    self.slots := ty_peg_list(ty_peg('a'));
    self.slots(1).tag := 'b';          -- line 8
    return;
  end;
  member procedure fill is
  begin
    self.items.extend(2);              -- line 13
    self.items(2) := self.items(1);    -- line 14
    self.slots(1).tag := 'c';          -- line 15
  end;
  member procedure fill_x(self in out nocopy ty_tray) is
  begin
    self.items.extend(2);              -- line 19
    self.items(2) := self.items(1);    -- line 20
    self.slots(1).tag := 'c';          -- line 21
  end;
  member function first_tag return varchar2 is
  begin
    return self.slots(1).tag;          -- line 25
  end;
end;
/
