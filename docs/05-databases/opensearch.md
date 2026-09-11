# OpenSearch

현재 matrix는 네 조합을 분리합니다.

| Test | Engine | Index | Search 폭 |
|---|---|---|---|
| T21 | Lucene | HNSW | candidate_k |
| T23 | Faiss | HNSW | ef_search |
| T25 | Faiss | IVF | nprobes |
| T27 | JVector | DiskANN | candidate_k |

## Lucene HNSW

Lucene engine은 `ef_search`를 무시하고 query의 `k`를 traversal 폭으로 사용합니다. 하네스는 응답 `size=10`은 고정한 채 candidate `k` 그리드를 전부 측정합니다. 존재하지 않는 ef 값을 결과에 기록하지 않습니다.

## Faiss

Faiss HNSW는 `method_parameters.ef_search`를 조정합니다. Faiss IVF는 먼저 training index에 벡터를 올리고 model API로 `nlist=128` model을 학습한 뒤, model ID를 참조하는 대상 index를 생성합니다. 검색 폭은 `nprobes`입니다.

적재 batch마다 refresh하지 않고 전체 적재 후 refresh와 force-merge를 수행한 뒤 측정합니다.

## JVector DiskANN

JVector plugin은 opensearch-knn과 같은 node에서 공존할 수 없으므로 `docker/opensearch-jvector/Dockerfile`로 별도 이미지를 만들고 port 19200의 별도 service/volume을 사용합니다. candidate `k`를 조정하고 top-10만 반환합니다.

## 자원과 크기

두 OpenSearch service 모두 4 vCPU/8GiB, JVM heap 4GiB입니다. heap 선점 때문에 RAM을 live working set으로 해석하지 않습니다. `_stats/store` 크기를 기록하지만 이는 OpenSearch가 보고하는 index store 범위입니다.

## 2026-09-11 전체 sweep 실측

[새 실험 보고서](../07-results/sweep-results-20260911.md)의 이 DB 측정은 다음과 같습니다. 각 범위는 **모든 검색 파라미터와 세 재구축 회차**를 포함합니다. 최소 p95와 최대 Recall이 같은 점이라는 뜻은 아닙니다.

| 구성 | 실제 검색 그리드 | 점 수 | Recall@10 범위 | 전체 p95 ms 범위 |
|---|---|---:|---:|---:|
| T21 / Lucene / HNSW | candidate_k: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 27 | 0.851031–0.997938 | 4.948–13.822 |
| T23 / Faiss / HNSW | ef_search: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 27 | 0.729897–0.991753 | 5.043–22.742 |
| T25 / Faiss / IVF | nprobes: 1, 2, 4, 8, 16, 32, 64, 96, 128 | 27 | 0.695361–0.969588 | 4.994–38.609 |
| T27 / JVector / DiskANN | candidate_k: 10, 20, 40, 80, 120, 200, 400, 800, 1000 | 27 | 0.340206–0.999485 | 8.897–85.424 |

모든 점을 [전체 산포도](../07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.svg)에 표시했습니다. 같은 파라미터의 반복 변동과 CPU·RAM·QPS는 [반복 집계](../07-results/assets/sweep-20260911-220549/vector-db-summary.csv)와 [원시값](../07-results/assets/sweep-20260911-220549/all-measurements.json)을 함께 확인합니다. Recall 0.90·0.95는 참고선이며 낮은 품질의 점도 제거하지 않습니다.

## 참고

- [OpenSearch k-NN methods and engines](https://docs.opensearch.org/latest/mappings/supported-field-types/knn-methods-engines/)
- [OpenSearch approximate k-NN과 IVF training](https://docs.opensearch.org/latest/vector-search/vector-search-techniques/approximate-knn/)
- [OpenSearch JVector plugin](https://docs.opensearch.org/latest/install-and-configure/additional-plugins/opensearch-jvector/)
