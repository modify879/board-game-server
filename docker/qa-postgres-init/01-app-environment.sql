-- QA DB 표식. 볼륨이 처음 만들어질 때만 실행된다. QaProfileGuard 가 기동 시 이 값을 읽는다.
create table app_environment (environment varchar(32) not null primary key);
insert into app_environment values ('qa');
