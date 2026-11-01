create or replace procedure use_f is
  l_r pk_f.t_row;
begin
  l_r.items := pk_f.t_nums();
  pk_f.s_rec.code := 'w';
  dbms_output.put_line(pk_f.s_rec.code);
end;
/
