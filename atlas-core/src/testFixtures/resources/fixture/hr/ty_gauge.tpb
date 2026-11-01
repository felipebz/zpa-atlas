create or replace type body ty_gauge as
  member procedure bump is
  begin
    if self.a > 0 then
      self.b := 'up';
    end if;
  end;
end;
/
