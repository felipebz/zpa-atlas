create or replace package text_tags is
  procedure build;
end text_tags;
/
create or replace package body text_tags is
  -- build writes <vFixTag> for the XML
  procedure build is
    v varchar2(200);
  begin
    v := '<vFixTag>' || '1' || '</vFixTag>';
    $if false $then
      v := 'VFIXTAG inactive';
    $end
  end;
end text_tags;
/
