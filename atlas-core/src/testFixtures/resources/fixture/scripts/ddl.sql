truncate table company;
alter table company add constraint ck_id2 check (st_active in ('S', 'N'));
create index ix_b on company (dt_changed);
drop table t_copy2;
