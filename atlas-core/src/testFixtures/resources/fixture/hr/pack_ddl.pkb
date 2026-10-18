create or replace package body pack_ddl is
  procedure run(p_table in varchar2) is
  begin
    execute immediate 'truncate table company';
    execute immediate 'truncate table hr.event_log reuse storage';
    execute immediate 'truncate table ' || p_table;
    execute immediate 'truncate table syn_company';
    execute immediate 'drop table send_queue purge';
    execute immediate 'drop table no_such_table';
    execute immediate 'drop table v_company';
    execute immediate 'drop view v_company';
    execute immediate 'drop package pack_calls';
    execute immediate 'drop package body pack_calls';
    execute immediate 'drop type ty_point force';
    execute immediate 'drop procedure proc_standalone';
    execute immediate 'drop sequence seq_missing';
    execute immediate 'drop synonym syn_company';
    execute immediate 'drop public synonym user_tables';
    execute immediate 'drop index ix_company';
    execute immediate 'analyze table company delete statistics';
    execute immediate 'alter table company enable constraint pk_company';
    execute immediate 'alter table company add constraint ck_id check (id > 0)';
    execute immediate 'alter package pack_calls compile body';
    execute immediate 'create sequence seq_new start with 1';
    execute immediate 'create table t_copy as select id, company_name from company where id = 1';
    execute immediate 'alter session set nls_numeric_characters = '',.''';
    execute immediate 'grant select on company to scott';
  end;
end pack_ddl;
/
