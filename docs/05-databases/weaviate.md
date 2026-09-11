# Weaviate

Native engine의 HNSW(T07/T08)와 HFresh(T09/T10)을 비교합니다. 검색은 GraphQL, schema와 적재는 REST를 사용하며 `vectorizer: none`으로 고정 embedding을 그대로 넣습니다.

## HNSW

- build: `maxConnections=16`, `efConstruction=128`
- search: class `vectorIndexConfig.ef`

`ef`는 요청별 값이 아니라 class 설정이므로 calibration 후보마다 schema GET/PUT을 수행합니다. 이 설정 변경은 검색 timer 밖입니다.

## HFresh

- `vectorIndexType=hfresh`
- build: `maxPostingSizeKB=48`, `replicas=4`
- search: mutable `searchProbe`
- 1-bit RQ 사용을 결과 index parameters에 기록

Docker는 `ASYNC_INDEXING=true`입니다. 적재 후 `/v1/nodes?output=verbose&class=...`의 object count와 `vectorQueueLength=0`을 확인하기 전에는 측정하지 않습니다.

## 필터와 비용

필터 property는 schema 생성 전에 YAML에 타입을 선언해야 합니다. 1%/10%/50% workload용 `benchmark_selectivity_01/10/50`도 text property로 선언돼 있습니다. GraphQL query 문자열 생성과 parsing은 search timer 안에 있으므로 결과는 Weaviate 서버 알고리즘만의 시간이 아닙니다.

순수 index size를 안정적으로 분리하는 API를 사용하지 않아 `index_size_bytes=-1`입니다.

## 참고

- [Weaviate vector index 설정](https://docs.weaviate.io/weaviate/config-refs/indexing/vector-index)
- [Weaviate vector index 개념](https://docs.weaviate.io/weaviate/concepts/vector-index)
