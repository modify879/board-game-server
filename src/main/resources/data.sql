-- Hibernate 는 JPA 연관관계 없이 FK 를 만들지 못한다. wallet 이 user 의 엔티티를 참조하지 않도록
-- 연관관계 대신 이 DDL 로 건다. 부팅마다 돌지만 이미 있으면 아무 일도 하지 않는다(멱등).
-- PostgreSQL 의 ALTER TABLE ADD CONSTRAINT 에는 IF NOT EXISTS 가 없어 예외로 잡는다.
--
-- 손으로 한 번 실행하는 방식을 쓰지 않는 이유: Testcontainers 가 쓰는 빈 DB 에는 제약이 없어
-- 번역 경로(WalletRepositoryAdapter.translate)를 테스트할 수 없다. 부팅마다 도는 멱등 DDL 이면
-- 개발 DB·테스트 컨테이너 양쪽에 똑같이 적용된다.
--
-- 문장 구분자를 기본값 ';' 대신 '@@@'(spring.sql.init.separator)로 바꿨다 — 스프링의 기본
-- 스크립트 분할기는 $$ 블록을 모르고 첫 ';' 에서 잘라 "Unterminated dollar quote" 로 깨진다.
do $$ begin
    alter table wallets
        add constraint fk_wallets_user foreign key (user_id) references users(id);
exception when duplicate_object then null;
end $$
@@@
do $$ begin
    alter table holdem_seats
        add constraint fk_holdem_seats_table foreign key (table_id) references holdem_tables(id);
exception when duplicate_object then null;
end $$
@@@
do $$ begin
    alter table holdem_seats
        add constraint fk_holdem_seats_user foreign key (user_id) references users(id);
exception when duplicate_object then null;
end $$
@@@
-- state 에는 인덱스가 없다. fillfactor 를 낮춰 페이지에 여유를 남기면 UPDATE 가 HOT(index 재작성 없이
-- 같은 페이지 안에서 갱신)으로 처리돼 인덱스 블로트가 생기지 않는다. 행 수 = 진행 중인 테이블 수라
-- autovacuum 을 기본 스케일(테이블 크기 비례)이 아니라 고정 임계치로 걸어, 몇 행 안 되는 테이블도
-- 죽은 튜플이 쌓이자마자 청소되게 한다.
alter table holdem_hand_in_progress
    set (fillfactor = 70, autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 50)
@@@
do $$ begin
    alter table holdem_hand_in_progress
        add constraint fk_hand_in_progress_table foreign key (table_id) references holdem_tables(id);
exception when duplicate_object then null;
end $$
@@@
-- ddl-auto: update 가 version 칼럼을 추가하면 기존 행은 NULL 이 되어, Hibernate 의 낙관적 락이 깨진다
update holdem_hand_in_progress set version = 0 where version is null
