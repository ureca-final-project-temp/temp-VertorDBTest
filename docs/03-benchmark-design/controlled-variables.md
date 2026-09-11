# Controlled Variables

## 고정·검증 항목

| 변수 | 값 | 강제 방법 |
|---|---|---|
| embedding | 같은 BGE-M3 1024차원 JSONL | 입력 SHA-256 기록 |
| source of truth | 같은 document/chunk snapshot | PostgreSQL 동기화 및 hash/count 검증 |
| metric | cosine | store와 불일치 시 거부 |
| topK | 10 | matrix runner |
| target Recall | 0.90, 0.95 ±0.01 | matrix와 selector |
| query split | calibration 100 / evaluation 200 | query-type 층화 SHA-256 |
| 부하 | concurrency 10, warm-up 1, measurement 5 | matrix runner |
| 반복 | 3~5 전체 재구축 | 스크립트 parameter validation |
| 자원 | 대상 합계 4 vCPU / 8GiB / swap 없음 | `docker inspect` 불일치 시 중단 |

인덱스 생성 파라미터는 결과의 `index_parameters`, 검색 폭은 `search_parameters`에 기록합니다. 서로 다른 계열에 존재하지 않는 파라미터를 억지로 같게 만들지 않습니다.

## 버전

| 구성 | 기본 버전 |
|---|---:|
| Java / Spring Boot | 21 / 4.1.1 |
| PostgreSQL / pgvector | 17 / 0.8.6 |
| Qdrant | 1.19.0 |
| Weaviate | 1.39.3 |
| Milvus | 3.0.1 |
| OpenSearch | 3.8.0 |

OpenSearch JVector는 공식 JVector plugin을 설치한 별도 이미지에서 실행합니다.

## 자원 예산

| 대상 | 컨테이너별 배분 | 합계 |
|---|---|---|
| pgvector/Qdrant/Weaviate/OpenSearch | 대상 단일 컨테이너 4 vCPU/8GiB | 4 vCPU/8GiB |
| Milvus | 본체 3 vCPU/6656MiB, etcd 0.5/512MiB, MinIO 0.5/1GiB | 4 vCPU/8GiB |

모든 컨테이너의 memory와 memory+swap limit를 같게 둡니다. PostgreSQL은 외부 DB 프로필에서 원본 저장소로 함께 뜨지만 검색 대상 자원 합계에서는 제외합니다.

## 남는 환경 변수

클라이언트 JVM 자원, JDBC/JSON/GraphQL 직렬화 차이, Windows Docker Desktop 포트포워드, OpenSearch JVM heap 선점은 동일화하지 못합니다. 따라서 결과는 알고리즘 microbenchmark가 아니라 이 애플리케이션 검색 경로의 end-to-end 지표입니다.
