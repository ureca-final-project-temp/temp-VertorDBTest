# Weaviate

Native engine의 HNSW(T07)와 HFresh(T09)을 비교합니다. 검색은 GraphQL, schema와 적재는 REST를 사용하며 `vectorizer: none`으로 고정 embedding을 그대로 넣습니다.

## HNSW

- build: `maxConnections=16`, `efConstruction=128`
- search: class `vectorIndexConfig.ef`

`ef`는 요청별 값이 아니라 class 설정이므로 sweep의 각 검색 파라미터를 적용할 때 schema GET/PUT을 수행합니다. 이 설정 변경은 검색 timer 밖입니다.

## HFresh

- `vectorIndexType=hfresh`
- build: `maxPostingSizeKB=48`, `replicas=4`
- search: mutable `searchProbe`
- 1-bit RQ 사용을 결과 index parameters에 기록

Docker는 `ASYNC_INDEXING=true`입니다. 적재 후 `/v1/nodes?output=verbose&class=...`의 object count와 `vectorQueueLength=0`을 확인하기 전에는 측정하지 않습니다.

## 필터와 비용

필터 property는 schema 생성 전에 YAML에 타입을 선언해야 합니다. 1%/10%/50% workload용 `benchmark_selectivity_01/10/50`도 text property로 선언돼 있습니다. GraphQL query 문자열 생성과 parsing은 search timer 안에 있으므로 결과는 Weaviate 서버 알고리즘만의 시간이 아닙니다.

이번 전체 sweep에서 HNSW의 전체 p95는 21.497–29.331ms, HFresh는 30.945–70.745ms였습니다. 이는 각 인덱스의 모든 파라미터와 세 회차를 포함한 범위입니다. GraphQL 문자열 생성·전송·응답 처리 비용은 타이머에 포함되지만, 이번 실험은 그 비용과 서버 인덱스 비용을 따로 측정하지 않았습니다. 따라서 지연의 원인을 GraphQL만으로 단정하거나 gRPC 변경 효과를 수치로 주장하지 않습니다. 현재 검색 경로는 [어댑터 정책](../06-implementation/adapter-policy.md)에 명시합니다.

순수 index size를 안정적으로 분리하는 API를 사용하지 않아 `index_size_bytes=-1`입니다.

## 2026-09-11 전체 sweep 실측

[새 실험 보고서](../07-results/sweep-results-20260911.md)의 이 DB 측정은 다음과 같습니다. 각 범위는 **모든 검색 파라미터와 세 재구축 회차**를 포함합니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T07 / Native / hnsw | ef: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 27 | 0.645876–0.973711 | 21.497–29.331 |
| T09 / Native / hfresh | searchProbe: 8, 16, 32, 64, 128, 256, 512, 1024 | 24 | 0.959519–0.973711 | 30.945–70.745 |

모든 점을 [전체 산포도](../07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.svg)에 표시했습니다. 같은 파라미터의 반복 변동과 CPU·RAM·QPS는 [반복 집계](../07-results/assets/sweep-20260911-220549/vector-db-summary.csv)와 [원시값](../07-results/assets/sweep-20260911-220549/all-measurements.json)을 함께 확인합니다. Recall 0.90·0.95는 참고선이며 낮은 품질의 점도 제거하지 않습니다.

## 참고

- [Weaviate vector index 설정](https://docs.weaviate.io/weaviate/config-refs/indexing/vector-index)
- [Weaviate vector index 개념](https://docs.weaviate.io/weaviate/concepts/vector-index)
