# pgvector

PostgreSQL 확장입니다. 현재 matrix는 PostgreSQL engine의 HNSW(T01/T02)와 IVFFlat(T03/T04)을 측정합니다.

## 설정과 수명주기

`vector.pgvector.index-type`은 `hnsw` 또는 `ivfflat`입니다.

- HNSW: `m=16`, `ef_construction=128`; 검색 `hnsw.ef_search`
- IVFFlat: `lists=10`; 검색 `ivfflat.probes`

IVFFlat은 데이터가 적재된 뒤 유효한 centroid를 만들 수 있으므로 manager가 table/create를 준비하고 적재 후 인덱스 ready 상태를 확인합니다. 작은 10k 테이블에서 planner의 exact sequential scan이 끼지 않도록 기본 benchmark는 `enable_seqscan=off`를 검색 세션에 적용합니다.

검색 timer에는 JDBC parameter 설정과 SQL 실행·row mapping이 포함됩니다.

## 필터

metadata는 JSONB이고 `metadata ->> key = value`로 필터합니다. 기본 하네스는 metadata용 별도 B-tree/GIN을 만들지 않으므로 선택도별 `filtered_*` 결과를 반드시 따로 봅니다. 운영 설계를 검증할 때는 pgvector iterative scan과 실제 metadata index 전략을 별도 case로 추가해야 합니다.

## 크기

실제 생성한 HNSW 또는 IVFFlat 인덱스 이름을 사용해 `pg_relation_size`를 기록합니다.

## 참고

- [pgvector 공식 문서](https://github.com/pgvector/pgvector)
