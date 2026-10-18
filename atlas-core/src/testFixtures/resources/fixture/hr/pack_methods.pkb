create or replace package body pack_methods is
  function f_x(p number) return varchar2 is begin return null; end;

  procedure run(i_doc in t_doc_tab) is
    v varchar2(4000);
    n number;
  begin
    if i_doc(1).items.exists(1) then
      n := i_doc(1).line_rec(1).line_no;
    end if;
    n := i_doc(1).items.next(n);
    v := f_x(1).path(f_x(2));
    select e.msg.extract(f_x(3)).getstringval() into v from event_log e;
    select x.column_value.extract('/a').getstringval() into v from table(xmlsequence(extract(xmltype('<a/>'), '/a'))) x;
    select elem.extract('elem') into v from table(xmlsequence(extract(xmltype('<a/>'), '/a'))) elem;
    select hr.event_log.msg.extract('/a').getstringval() into v from hr.event_log;
    select pack_methods.f_x(4) into v from event_log pack_methods;
    select pack_methods.f_x(5) into v from dual;
  end;
end;
/
