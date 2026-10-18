create or replace type body ty_bag as
  member function g(n number) return number is begin return n; end;
  member function nth(i number) return number is
    k number;
  begin
    k := entries.next(i);
    entries.trim(1);
    k := g(1);
    k := self.g(2);
    k := entries(pack_methods.f_x(1));
    return self.entries(self.entries.first);
  end;
end;
/
