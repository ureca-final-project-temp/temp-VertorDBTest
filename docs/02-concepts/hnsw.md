# HNSW

이 프로젝트가 다섯 DB에서 공통으로 사용하는 ANN 인덱스입니다.

## 구조

Hierarchical Navigable Small World. 벡터를 노드로, 이웃 관계를 간선으로 하는 그래프를 계층으로 쌓습니다.

```text
Layer 2    ●───────────────●          성긴 그래프, 멀리 점프
           │               │
Layer 1    ●───●───────●───●          중간
           │   │       │   │
Layer 0    ●─●─●─●─●─●─●─●─●          전체 벡터, 촘촘한 이웃
```

검색은 위층에서 대략적인 위치로 점프한 뒤 아래층에서 정밀하게 좁힙니다.
전수 비교 없이 가까운 이웃에 도달할 수 있습니다.

## 파라미터

| 파라미터 | 시점 | 의미 | 올리면 |
|---|---|---|---|
| `M` | 인덱스 생성 | 노드당 이웃 수 | 정확도 ↑, 메모리 ↑, 빌드 시간 ↑ |
| `ef_construction` | 인덱스 생성 | 생성 시 탐색 후보 수 | 그래프 품질 ↑, 빌드 시간 ↑ |
| `ef_search` | 검색 | 검색 시 탐색 후보 수 | Recall ↑, latency ↑ |

**생성 파라미터는 인덱스를 다시 만들어야 바뀌고, 검색 파라미터는 질의마다 바꿀 수 있습니다.**
그래서 Recall 튜닝은 검색 파라미터로 합니다.

## 이 프로젝트의 고정값

생성 파라미터는 전 DB 동일합니다.

```text
M = 16
ef_construction = 128
```

검색 파라미터는 DB마다 이름이 다릅니다.

| DB | 검색 파라미터 이름 | 적용 방식 |
|---|---|---|
| pgvector | `ef_search` | 검색 세션에 `set_config('hnsw.ef_search', ...)` |
| Qdrant | `hnsw_ef` | 요청 body의 `params` |
| Weaviate | `ef` | 클래스 스키마 갱신 (요청별 지정 불가) |
| Milvus | `ef` | 요청 body의 `searchParams.params` |
| OpenSearch | `ef_search` | knn 쿼리의 `method_parameters` |

`BenchmarkRunner`가 DB별로 올바른 키를 고릅니다.

```java
String key = switch (store.database().toLowerCase()) {
    case "qdrant" -> "hnsw_ef";
    case "weaviate", "milvus" -> "ef";
    default -> "ef_search";
};
```

## 자동 튜닝

`searchParameters: {}`로 요청하면 후보 `[10, 20, 40, 80, 120, 200, 400, 800, 1000]` 전체를
무필터 270개 질의에 대해 본 측정과 같은 warm-up·동시성·반복 횟수로 시험한 뒤,
목표 Recall 구간에 드는 **가장 작은** 값을 고릅니다.

```text
ef_search=10   → Recall 0.71
ef_search=20   → Recall 0.79
ef_search=40   → Recall 0.86
ef_search=80   → Recall 0.91   ← 목표 0.90 ±0.01 충족, 여기서 선택
ef_search=120  → Recall 0.93
```

구간에 드는 후보가 없으면 목표와 가장 가까운 후보를 고르고 `CLOSEST_AVAILABLE`로 기록합니다.
이 표시가 붙은 행은 "동일 Recall 비교"에 사용할 수 없습니다.
`WITHIN_TOLERANCE`여도 본 측정의 `target_met=false`이면 튜닝 이후 Recall이 변한 것이므로
직접 비교에서 제외합니다.

명시값을 쓰려면 시나리오에 직접 넣습니다.

```json
"searchParameters": {"ef_search": 120}
```

## 주의: 비동기 인덱싱

여러 DB가 적재 직후 인덱스를 아직 만들지 않은 상태로 검색을 받습니다.
그 상태로 측정하면 HNSW가 아니라 exact scan을 측정하게 됩니다.

`VectorIndexManager.awaitReady()`가 제품별 장벽을 담당합니다.

- **Qdrant** — `indexed_vectors_count`가 전체 건수에 도달하고 status가 `green`이 될 때까지 대기.
  exact-scan 임계값 아래에서는 이 건수 대기만 생략하고 payload index 검증은 수행
- **Milvus** — `flush` 후 `indexState=Finished`, `indexedRows >= 전체`, `pendingRows == 0`까지 대기
- **OpenSearch** — bulk 적재에 `refresh=wait_for`
- **pgvector / Weaviate** — 동기 경로라 별도 장벽 없음

## 주의: ef를 올려도 느려지지 않는 구간

`ef`를 크게 올렸는데 특정 percentile이 반응하지 않으면, 그 경로는 HNSW를 타고 있지 않다는 뜻입니다.
대부분 필터 필드의 index 누락이 원인입니다.

실제 사례는 [../07-results/analysis.md](../07-results/analysis.md)에 있습니다.

## 관련 문서

- [exact-vs-ann.md](exact-vs-ann.md)
- [recall.md](recall.md)
- [../05-databases/](../05-databases/)
