create or replace function md5(p varchar2) return varchar2 is
begin
  return p;
end;
/
create or replace function cripto_a(p varchar2) return varchar2 is
  function md5(x varchar2) return varchar2 is
  begin
    return x;
  end;
begin
  return md5(p);
end;
/
create or replace function cripto_b(p varchar2) return varchar2 is
  function md5(x varchar2) return varchar2;
  function md5(x varchar2) return varchar2 is
  begin
    return x;
  end;
begin
  return md5(p);
end;
/
create or replace function cripto_c(p varchar2) return varchar2 is
  r varchar2(100);
begin
  declare
    function md5(x varchar2) return varchar2 is
    begin
      return x;
    end;
  begin
    r := md5(p);
  end;
  return r;
end;
/
create or replace function cripto_d(p varchar2) return varchar2 is
begin
  return md5(p);
end;
/
