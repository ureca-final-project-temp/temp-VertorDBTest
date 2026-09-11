# pgvector

PostgreSQL 확장입니다. 현재 matrix는 PostgreSQL engine의 HNSW(T01)와 IVFFlat(T03)을 측정합니다.

## 설정과 수명주기

`vector.pgvector.index-type`은 `hnsw` 또는 `ivfflat`입니다.

- HNSW: `m=16`, `ef_construction=128`; 검색 `hnsw.ef_search`
- IVFFlat: `lists=10`; 검색 `ivfflat.probes`

IVFFlat은 `CREATE INDEX` 시점의 데이터로 centroid를 학습합니다. `rebuild()`는 테이블만 만들고, 전체 벡터를 적재한 뒤 `awaitReady()`가 실제 행 수를 확인하고 IVFFlat 인덱스를 생성합니다. 인덱스 생성이 끝나야 검색 파라미터 sweep을 시작하며, 이 생성 시간은 `time_to_index_ready_ms`에 포함됩니다. HNSW는 기존처럼 빈 테이블에 인덱스를 만든 뒤 벡터를 적재합니다.

작은 10k 테이블에서 planner의 exact sequential scan이 끼지 않도록 기본 benchmark는 `enable_seqscan=off`를 검색 세션에 적용합니다.

검색 timer에는 JDBC parameter 설정과 SQL 실행·row mapping이 포함됩니다.

## 필터

metadata는 JSONB이고 `metadata ->> key = value`로 필터합니다. 기본 하네스는 metadata용 별도 B-tree/GIN을 만들지 않으므로 선택도별 `filtered_*` 결과를 반드시 따로 봅니다. 운영 설계를 검증할 때는 pgvector iterative scan과 실제 metadata index 전략을 별도 case로 추가해야 합니다.

## 크기

실제 생성한 HNSW 또는 IVFFlat 인덱스 이름을 사용해 `pg_relation_size`를 기록합니다.

## 2026-09-11 전체 sweep 실측

[새 실험 보고서](../07-results/sweep-results-20260911.md)의 이 DB 측정은 다음과 같습니다. 각 범위는 **모든 검색 파라미터와 세 재구축 회차**를 포함합니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T01 / PostgreSQL / hnsw | ef_search: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 27 | 0.652577–0.957216 | 3.964–55.776 |
| T03 / PostgreSQL / ivfflat | probes: 1, 2, 3, 4, 5, 6, 8, 10 | 24 | 0.848454–0.984536 | 19.695–76.895 |

모든 점을 [전체 산포도](../07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.svg)에 표시했습니다. 같은 파라미터의 반복 변동과 CPU·RAM·QPS는 [반복 집계](../07-results/assets/sweep-20260911-220549/vector-db-summary.csv)와 [원시값](../07-results/assets/sweep-20260911-220549/all-measurements.json)을 함께 확인합니다. Recall 0.90·0.95는 참고선이며 낮은 품질의 점도 제거하지 않습니다.

## 참고

- [pgvector 공식 문서](https://github.com/pgvector/pgvector)
