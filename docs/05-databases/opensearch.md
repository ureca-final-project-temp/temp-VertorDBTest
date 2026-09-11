# OpenSearch

현재 matrix는 네 조합을 분리합니다.

| Test | Engine | Index | Search 폭 |
|---|---|---|---|
| T21/T22 | Lucene | HNSW | candidate_k |
| T23/T24 | Faiss | HNSW | ef_search |
| T25/T26 | Faiss | IVF | nprobes |
| T27/T28 | JVector | DiskANN | candidate_k |

## Lucene HNSW

Lucene engine은 `ef_search`를 무시하고 query의 `k`를 traversal 폭으로 사용합니다. 하네스는 응답 `size=10`은 고정한 채 candidate `k`를 조정해 Recall점을 찾습니다. 존재하지 않는 ef 값을 결과에 기록하지 않습니다.

## Faiss

Faiss HNSW는 `method_parameters.ef_search`를 조정합니다. Faiss IVF는 먼저 training index에 벡터를 올리고 model API로 `nlist=128` model을 학습한 뒤, model ID를 참조하는 대상 index를 생성합니다. 검색 폭은 `nprobes`입니다.

적재 batch마다 refresh하지 않고 전체 적재 후 refresh와 force-merge를 수행한 뒤 측정합니다.

## JVector DiskANN

JVector plugin은 opensearch-knn과 같은 node에서 공존할 수 없으므로 `docker/opensearch-jvector/Dockerfile`로 별도 이미지를 만들고 port 19200의 별도 service/volume을 사용합니다. candidate `k`를 조정하고 top-10만 반환합니다.

## 자원과 크기

두 OpenSearch service 모두 4 vCPU/8GiB, JVM heap 4GiB입니다. heap 선점 때문에 RAM을 live working set으로 해석하지 않습니다. `_stats/store` 크기를 기록하지만 이는 OpenSearch가 보고하는 index store 범위입니다.

## 참고

- [OpenSearch k-NN methods and engines](https://docs.opensearch.org/latest/mappings/supported-field-types/knn-methods-engines/)
- [OpenSearch approximate k-NN과 IVF training](https://docs.opensearch.org/latest/vector-search/vector-search-techniques/approximate-knn/)
- [OpenSearch JVector plugin](https://docs.opensearch.org/latest/install-and-configure/additional-plugins/opensearch-jvector/)
