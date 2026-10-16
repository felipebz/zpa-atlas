create or replace package pack_types is
  type t_company_tab is table of company%rowtype;
  subtype s_company_name is company.company_name%type;
  type r_pair is record (id company.id%type, counter pack_process.g_counter%type);
  g_last pack_process.r_event;
  g_last_msg pack_types.g_last.msg%type;
  g_last_id g_last.id%type;
  cursor c_active is select id from company where st_active = 'S';
  procedure load(p_id company.id%type, p_row company%rowtype);
  function name_of(p_id company.id%type) return s_company_name;
end pack_types;
/
