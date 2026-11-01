
-- needed so we can fill in N-sized generation after the initial
-- low-detail LODs have been generated
alter table FullData add column Regenerate bit null;

--batch--

create index FullDataRegenerateIndex on FullData (Regenerate) where Regenerate = 1;
