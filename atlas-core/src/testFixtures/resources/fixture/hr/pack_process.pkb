create or replace package body pack_process is g_private number;
  procedure write_log(p_msg varchar2) is
  begin
    insert into event_log (id, msg) values (1, p_msg); select c_kind into g_counter from dual; select pack_process.id into g_counter from send_queue pack_process; insert into event_log (id, msg) values (seq_event.nextval, pack_process.c_kind);
  end;
  function f_private return number is begin return 1; end;
  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date) is
    r company%rowtype; g_rec company%rowtype;
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
    write_log('x'); g_rec.id := 1;
  end;
  function f_next return number is begin return seq_event.nextval; end; procedure get_event(p_id in number, p_out out number, p_io in out number) is begin p_out := p_id; end;
  procedure run_job(p_a in number, p_b in varchar2, p_c in number, p_d in date, p_e in number default 0) is
    c sys_refcursor;
  begin
    insert into send_queue values (p_a, 'J', p_b); g_counter := pack_process.g_counter + 1;
    insert into send_queue (id, record_type) values (1, 'J'); g_rec.msg := pack_process.g_rec.msg;
    insert into send_queue set id = 1, record_type = 'J'; if pack_process.g_events(1).id = hr.pack_process.g_counter and pack_process.g_events.count > 0 then null; end if;
    execute immediate 'delete from company'; pack_process.g_events.delete; pack_process.g_events(1).id := 2;
    execute immediate 'delete from ' || p_b; get_event(p_a, g_counter, pack_process.g_counter);
    dbms_sql.parse(p_a, 'select 1 from dual', dbms_sql.native); g_counter := f_next + pack_process.f_next + f_private + fn_one + pkg_ext.g_val; pack_process.g_private := hr.pack_process.g_private;
    open c for 'select * from ' || p_b; for r in pack_process.c_pending loop null; end loop; g_counter := pack_process.g_missing; select seq_event.currval, hr.seq_event.nextval, other.seq_x.nextval, seq_missing.nextval, pack_process.f_next into g_counter, g_counter, g_counter, g_counter, g_counter from dual;
  end;
end pack_process;
/
