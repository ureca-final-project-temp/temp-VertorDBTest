# Vector DB 선정 가이드

이 문서는 제품 홍보표가 아니라 1차 의사결정용 체크리스트다. 최종 선택은 반드시 이 저장소의 고정 데이터·고정 Recall 실험 결과로 결정한다.

| 항목 | pgvector | Qdrant | Weaviate | Milvus | OpenSearch |
|---|---|---|---|---|---|
| 기본 구조 | PostgreSQL 확장 | 전용 Vector DB | 전용 Vector DB | 전용 Vector DB, 분산형 확장 가능 | 검색 엔진 + Vector |
| Source of Truth 동기화 | 가장 단순, 같은 트랜잭션 경계 가능 | 애플리케이션 outbox/재처리 필요 | 애플리케이션 outbox/재처리 필요 | 애플리케이션 outbox/재처리 필요 | 애플리케이션 outbox/재처리 필요 |
| HNSW | 지원 | 지원 | 지원 | 지원 | Lucene HNSW 사용 |
| Exact Search | 기본 순차 검색 가능 | exact 옵션 가능 | 주 사용 경로는 vector index | FLAT 계열 가능 | 스크립트/정확 검색은 별도 설계 필요 |
| Metadata Filter | SQL/JSONB | payload filter | inverted property filter | scalar/JSON filter | Query DSL filter |
| Hybrid Search | PostgreSQL FTS와 직접 조합 설계 | dense/sparse 조합 설계 | 내장 hybrid search 강점 | dense/sparse 및 rerank 조합 | lexical + neural hybrid 강점 |
| Multi-tenancy | 파티션/테이블/RLS 설계 | payload, shard 전략 | collection multi-tenancy | DB/collection/partition/partition key | index/alias/document-level 보안 설계 |
| Scale-out | PostgreSQL 운영 전략에 의존 | shard/replica cluster | multi-node shard/replica | 구성요소별 분산 확장 강점 | shard/replica cluster |
| 운영 복잡도 | 기존 PostgreSQL 팀이면 낮음 | 중간 | 중간 | standalone도 의존 구성 포함, distributed는 높음 | 기존 OpenSearch 팀이면 중간, 신규면 높음 |
| 먼저 검토할 상황 | 데이터 규모가 감당 가능하고 정합성·단순성이 최우선 | Vector 중심 API, 필터, 비교적 단순한 전용 DB | hybrid와 내장 데이터 기능을 빠르게 활용 | 매우 큰 규모와 독립 확장이 핵심 | 이미 검색 플랫폼이 있고 lexical/vector 통합이 핵심 |

## 판단 순서

1. PostgreSQL 단일 운영으로 목표 Recall의 p95/p99와 용량 요구를 만족하는지 본다. 만족하면 별도 동기화 계층을 추가할 이유부터 증명해야 한다.
2. 필터를 포함한 실제 질의 분포로 다시 측정한다. ANN 뒤 필터링 또는 필터 선택도에 따라 결과 수와 Recall이 크게 달라질 수 있다.
3. 장애 시 재구축 시간과 허용 RPO/RTO를 측정한다. 독립 Vector DB는 PostgreSQL 원본에서 idempotent하게 재적재할 수 있어야 한다.
4. 평균이 아니라 동일 Recall의 p95/p99, QPS, peak memory를 비교한다.
5. 예상 3년 데이터량, tenant 수, write/read 비율로 scale-out 필요성을 검증한다.

## 프로젝트에서 추가로 검증할 것

- Qdrant는 실제 필터 키에 payload index 및 tenant 설정을 적용한 별도 시나리오가 필요하다.
- pgvector filtered ANN은 필터 선택도별 결과 부족 여부와 iterative scan 설정을 확인해야 한다.
- Weaviate는 선언한 filter property와 실제 JSONL metadata type이 일치해야 한다.
- Milvus는 flush와 `indexedRows` 완료 후에만 측정해야 한다. 본 구현은 이 장벽을 포함한다.
- OpenSearch는 lexical/vector hybrid 요구가 있다면 pure vector 결과와 분리해 추가 실험한다.
- 백업/복구, rolling upgrade, 모니터링, 재색인 중 서비스 영향은 로컬 latency 실험과 별도의 운영 검증이다.

## 공식 참고 문서

- [pgvector HNSW, filtering, iterative scan](https://github.com/pgvector/pgvector)
- [Qdrant distributed deployment](https://qdrant.tech/documentation/scaling/distributed_deployment/)
- [Qdrant multitenancy](https://qdrant.tech/documentation/tutorials/multiple-partitions/)
- [Weaviate collection and multi-tenancy](https://docs.weaviate.io/weaviate/manage-collections)
- [Milvus deployment options](https://milvus.io/docs/install-overview.md)
- [Milvus architecture](https://milvus.io/docs/architecture_overview.md)
- [OpenSearch approximate k-NN](https://docs.opensearch.org/latest/vector-search/vector-search-techniques/approximate-knn/)
- [OpenSearch hybrid search](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/index/)

