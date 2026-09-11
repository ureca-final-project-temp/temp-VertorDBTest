# Architecture

이 문서는 측정값이 **어느 경계에서 생성되는지**를 설명합니다.

## 계층

```text
BenchmarkController  ─ POST /api/benchmarks/run
        ↓
BenchmarkRunner      ─ 시나리오 실행, 튜닝, 집계
        ↓
VectorStore (port)   ─ DB 중립 계약
        ↓
┌───────┬────────┬──────────┬────────┬────────────┐
pgvector  Qdrant   Weaviate   Milvus   OpenSearch    ← adapter
 (JDBC)   (REST)   (GraphQL)  (REST)     (REST)
```

Ground Truth는 이 경로 밖에서 만듭니다.

```text
VectorDatasetLoader ─> ExactSearchEngine (Java brute-force) ─> Exact Top-K
```

DB가 반환한 결과를 정답으로 쓰지 않습니다. 어떤 DB의 인덱스가 잘못 만들어져도 Recall이 떨어져서 드러납니다.

## 측정 타이머의 경계

```java
VectorSearchRequest request = request(query, scenario, effectiveParameters);  // 타이머 밖
long started = System.nanoTime();
List<VectorSearchResult> approximate = store.search(request);                 // ← 타이머 안
latency.record(System.nanoTime() - started, filtered);
double recall = recallCalculator.recallAtK(...);                              // 타이머 밖
```

타이머 안에 들어가는 것:

- 요청 직렬화 (JSON 배열 또는 GraphQL 문자열, pgvector는 바이너리 JDBC)
- 네트워크 왕복
- DB의 검색 실행
- 응답 역직렬화

타이머 밖에 있는 것: Controller, embedding, Ground Truth 계산, Recall 계산, 자원 샘플링, 파라미터 튜닝.

> 직렬화 비용이 DB마다 다르다는 점은 결과 해석에 영향을 줍니다.
> [../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)를 봅니다.

## 자원 샘플링

`ResourceCollector`가 별도 데몬 스레드에서 500ms마다 `docker stats --no-stream`을 실행합니다.
검색 스레드와 분리되어 있어 지연시간에 포함되지 않습니다.

- 측정 시작 직전 baseline 1회 — Block I/O write의 기준점으로만 사용합니다(유휴 CPU가 평균에 섞이지 않도록).
- 측정 종료 직후 동기 샘플 1회 — 측정 구간이 스케줄 주기보다 짧은 경우를 보완합니다.

## Source of Truth

원본 문서와 청크는 PostgreSQL에 보관합니다(`V1__source_of_truth.sql`, Spring Data JDBC).
`BenchmarkSourceOfTruthSynchronizer`가 벡터 JSONL을 200개 원문과 10,000개 청크로 동기화하고,
`benchmark_dataset_state`에 입력 SHA-256과 건수를 기록합니다. 동일 스냅샷이면 재적재하지 않으며,
불일치한 상태에서 `rebuildAndLoad=false`이면 실행을 거부합니다. Flyway는 기존 비어 있지 않은
개발 볼륨도 baseline 0에서 V1·V2를 적용합니다.

전용 Vector DB 프로필에서도 PostgreSQL이 함께 뜨지만 **검색 요청 경로와 자원 측정 대상에서는 제외**합니다.
pgvector 프로필에서만 PostgreSQL이 측정 대상입니다.

## 컴포넌트별 코드 위치

| 개념 | 파일 |
|---|---|
| 시나리오 실행·튜닝·집계 | `benchmark/BenchmarkRunner.java` |
| Exact Top-K | `benchmark/ExactSearchEngine.java` |
| Recall@K | `benchmark/RecallCalculator.java` |
| 목표 Recall 후보 선택 | `benchmark/RecallTargetSelector.java` |
| 지연시간 수집(필터/무필터 분리) | `benchmark/LatencyCollector.java` |
| 자원 샘플링 | `benchmark/ResourceCollector.java` |
| 결과 파일 출력 | `benchmark/ResultWriter.java` |
| PostgreSQL 원본 스냅샷 동기화 | `infrastructure/rdb/postgres/BenchmarkSourceOfTruthSynchronizer.java` |
| DB 중립 계약 | `port/VectorStore.java`, `port/VectorIndexManager.java` |
| DB별 구현 | `infrastructure/vector/<db>/` |

더 자세한 코드 지도는 [../06-implementation/code-architecture.md](../06-implementation/code-architecture.md)에 있습니다.
