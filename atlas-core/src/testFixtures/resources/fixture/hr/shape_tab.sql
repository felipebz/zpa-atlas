create table shape_tab of ty_shape (primary key (id)) object identifier is primary key;
create table shape_holder (id number, s ref ty_shape, x number);
