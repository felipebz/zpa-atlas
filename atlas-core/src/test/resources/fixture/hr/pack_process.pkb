create or replace package body pack_process is
  procedure write_log(p_msg varchar2) is
  begin
    insert into event_log (id, msg) values (1, p_msg);
  end;

  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date) is
    r company%rowtype;
  begin
    update company set st_active = 'S' where id = p_a;
    update company e set e.st_active = 'N' where e.id = p_a;
    update company e set x.st_active = 'N';
    update company set (company_name, st_active) = (select 'a', 'b' from dual);
    update company set row = r where id = p_a;
    update company set missing_column = 1;
    merge into company d using (select id from send_queue) s on (d.id = s.id)
      when matched then update set d.st_active = 'S' delete where d.company_name is null
      when not matched then insert (id, st_active) values (s.id, 'N');
    write_log('x');
  end;

  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date, p_e in number default 0) is
    c sys_refcursor;
  begin
    insert into send_queue values (p_a, 'J', p_b);
    insert into send_queue (id, record_type) values (1, 'J');
    insert into send_queue set id = 1, record_type = 'J';
    execute immediate 'delete from company';
    execute immediate 'delete from ' || p_b;
    dbms_sql.parse(p_a, 'select 1 from dual', dbms_sql.native);
    open c for 'select * from ' || p_b;
  end;
end pack_process;
/
