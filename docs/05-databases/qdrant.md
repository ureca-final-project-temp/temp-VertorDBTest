# Qdrant

Native HNSW(T05)를 측정하는 전용 Vector DB입니다. REST query API로 접근합니다.

## 설정

`src/main/resources/application-qdrant.yml`

```yaml
vector.qdrant:
  base-url: http://localhost:6333
  collection: benchmark_chunks
  dimension: 1024
  metric: COSINE
  hnsw-m: 16
  ef-construction: 128
  default-ef-search: 100
  full-scan-threshold: 10      # KB
  indexing-threshold: 10       # KB
  payload-index-fields:
    tenant: keyword
    tenant_id: keyword
    status: keyword
    access_level: keyword
    language: keyword
    category: keyword
```

## 컬렉션 생성

```json
PUT /collections/benchmark_chunks
{
  "vectors": {"size": 1024, "distance": "Cosine"},
  "hnsw_config": {"m": 16, "ef_construct": 128, "full_scan_threshold": 10},
  "optimizers_config": {"indexing_threshold": 10}
}
```

이어서 선언한 필드마다 payload index를 만듭니다.

```json
PUT /collections/benchmark_chunks/index?wait=true
{"field_name": "metadata.tenant_id", "field_schema": "keyword"}
```

## 검색

```json
POST /collections/benchmark_chunks/points/query
{
  "query": [...],
  "limit": 10,
  "with_payload": true,
  "params": {"hnsw_ef": 400, "exact": false},
  "filter": {"must": [{"key": "metadata.tenant_id", "match": {"value": "alpha"}}]}
}
```

## Sharp edges

### 선언한 payload index가 준비돼 있어야 합니다

이 하네스는 필터용 payload index를 생성하고 준비 상태를 확인합니다. 누락되면 의도한 필터 검색 조건과 다르므로 측정을 시작하지 않습니다. 실제 검색 경로는 필터 선택도와 내부 실행 계획의 영향도 받습니다.

과거 1차 실행에서 관측한 증상이며 이번 372개 sweep 수치와는 구분합니다.

```text
hnsw_ef  80 → 필터 질의 p95 130.74 ms
hnsw_ef 1000 → 필터 질의 p95 126.64 ms    ← 12.5배 올렸는데 변화 없음
```

특정 percentile이 `ef`에 반응하지 않으면 payload index와 요청 파라미터를 확인합니다. latency만으로 실제 검색 경로를 확정하지 않습니다.

`awaitReady()`가 `payload_schema`를 확인하고 선언한 필드가 없으면 측정을 시작하지 않고 실패합니다.
작은 컬렉션이라 indexed vector 대기를 생략하는 exact-scan 경로에서도 이 검증은 건너뛰지 않습니다.

```java
throw new IllegalStateException("Qdrant payload index is missing for " + missing
        + "; filtered search would fall back to a full scan");
```

이 결함으로 1차 실험이 무효가 된 경위는 [../07-results/analysis.md](../07-results/analysis.md)에 있습니다.

### 임계값 10 KB

기본 optimizer 설정에서는 작은 segment가 exact scan으로 남습니다.
`full_scan_threshold`와 `optimizers_config.indexing_threshold`를 모두 10 KB로 낮춰
강제로 HNSW를 만들게 합니다.

`awaitReady()`는 `indexed_vectors_count`가 전체 건수에 도달하고 status가 `green`이 될 때까지 기다립니다.
단, 추정 벡터 크기가 `full_scan_threshold`보다 작으면 Qdrant가 의도적으로 exact를 쓰므로
indexed vector 건수 대기만 건너뜁니다. payload index 존재 검사는 그대로 수행합니다.

### 내부 point id를 원본 id로 복원합니다

Qdrant는 point id로 UUID 또는 정수만 받습니다.
`chunk-000-00` 같은 문자열은 `UUID.nameUUIDFromBytes()`로 결정론적 변환합니다.
원본 id는 payload의 `id` 필드에 보관하고 검색 결과는 거기서 읽습니다.

### index_size_bytes 미지원

Qdrant는 인덱스 크기를 직접 제공하지 않아 `-1`로 기록됩니다.

## 확인

```powershell
Invoke-RestMethod http://localhost:6333/collections/benchmark_chunks |
  Select-Object -ExpandProperty result |
  Select-Object status, points_count, indexed_vectors_count, payload_schema
```

`payload_schema`에 `metadata.tenant_id` 등이 보여야 합니다.

## 남은 과제

실제 필터 키에 tenant 전용 설정(`is_tenant`)과 shard key를 적용한 별도 시나리오가 필요합니다.

## 코드

- `infrastructure/vector/qdrant/QdrantVectorStore.java`
- `infrastructure/vector/qdrant/QdrantIndexManager.java`
- `infrastructure/vector/qdrant/QdrantProperties.java`

## 2026-09-11 전체 sweep 실측

[새 실험 보고서](../07-results/sweep-results-20260911.md)의 이 DB 측정은 다음과 같습니다. 각 범위는 **모든 검색 파라미터와 세 재구축 회차**를 포함합니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T05 / Native / hnsw | hnsw_ef: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 27 | 0.880928–0.983505 | 3.566–22.513 |

모든 점을 [전체 산포도](../07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.svg)에 표시했습니다. 같은 파라미터의 반복 변동과 CPU·RAM·QPS는 [반복 집계](../07-results/assets/sweep-20260911-220549/vector-db-summary.csv)와 [원시값](../07-results/assets/sweep-20260911-220549/all-measurements.json)을 함께 확인합니다. Recall 0.90·0.95는 참고선이며 낮은 품질의 점도 제거하지 않습니다.

## 참고

- [Qdrant distributed deployment](https://qdrant.tech/documentation/scaling/distributed_deployment/)
- [Qdrant multitenancy](https://qdrant.tech/documentation/tutorials/multiple-partitions/)
