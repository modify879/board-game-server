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
