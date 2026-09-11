# OpenSearch

검색 엔진 + Vector. Lucene HNSW 엔진을 사용합니다.

## 설정

`src/main/resources/application-opensearch.yml`

```yaml
vector.opensearch:
  base-url: http://localhost:9200
  index: benchmark-chunks
  dimension: 1024
  metric: COSINE
  hnsw-m: 16
  ef-construction: 128
  default-ef-search: 100
```

컨테이너 환경변수:

```yaml
discovery.type: single-node
DISABLE_SECURITY_PLUGIN: "true"
OPENSEARCH_JAVA_OPTS: "-Xms4g -Xmx4g"
```

## 인덱스 매핑

```json
PUT /benchmark-chunks
{
  "settings": {"index": {"knn": true}},
  "mappings": {"properties": {
    "id": {"type": "keyword"},
    "documentId": {"type": "keyword"},
    "chunkId": {"type": "keyword"},
    "content": {"type": "text", "index": false},
    "metadata": {"type": "object", "dynamic": true},
    "embedding": {
      "type": "knn_vector", "dimension": 1024,
      "method": {"name":"hnsw","engine":"lucene","space_type":"cosinesimil",
                 "parameters":{"m":16,"ef_construction":128}}
    }
  }}
}
```

## 검색

```json
POST /benchmark-chunks/_search
{
  "size": 10,
  "_source": ["id", "documentId", "chunkId"],
  "query": {"knn": {"embedding": {
    "vector": [...], "k": 10,
    "method_parameters": {"ef_search": 20},
    "filter": {"bool": {"filter": [
      {"term": {"metadata.tenant_id.keyword": "alpha"}},
      {"term": {"metadata.status.keyword": "active"}}
    ]}}
  }}}
}
```

## Sharp edges

### 낮은 목표 Recall에 도달할 수 없습니다

최소 후보 `ef_search=10`에서 이미 Recall 0.93이 나옵니다.

```text
ef_search=10 → Recall 0.9303    ← 목표 0.80, 0.90 모두 도달 불가
ef_search=20 → Recall 0.9413
```

후보는 `candidate >= topK` 조건으로 걸러지므로 10 밑으로 내려갈 수 없습니다.
결과적으로 0.80·0.90 행은 `CLOSEST_AVAILABLE`이 되어 비교에 쓸 수 없습니다.

해결하려면 `ef`가 아니라 `M` / `ef_construction`을 낮춰야 하며 현재 미구현입니다.
[../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)를 봅니다.

### 메모리 수치가 사용량이 아닙니다

`-Xms4g -Xmx4g`로 JVM heap을 선점하므로 `peak_memory_bytes`는 약 4.7 GiB로 고정됩니다.
이 값을 pgvector의 165 MiB와 나란히 두고 "메모리를 많이 쓴다"고 읽으면 안 됩니다.

### metadata가 dynamic mapping입니다

`metadata`가 `dynamic: true`라 `tags` 배열과 `title` 전문까지 자동 색인됩니다.
필터에 쓰지 않는 필드도 인덱스 크기와 메모리에 영향을 줍니다.
문자열 필드는 `text` + `keyword` multifield로 매핑되므로 필터는 `.keyword`를 붙입니다.

```java
String field = "metadata." + key + (value instanceof String ? ".keyword" : "");
```

### 적재가 느립니다

bulk 요청에 `refresh=wait_for`를 걸어 적재 완료를 보장합니다.
그만큼 `upsert_ms`가 다른 DB보다 크게 나옵니다. 이 값은 검색 지연시간과 별개로 읽습니다.

### index_size_bytes 지원

`/_stats/store`의 `size_in_bytes`를 보고합니다. pgvector와 함께 실제 값을 제공하는 둘 중 하나입니다.

## 확인

```powershell
Invoke-RestMethod 'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=60s'
Invoke-RestMethod http://localhost:9200/benchmark-chunks/_count
Invoke-RestMethod http://localhost:9200/benchmark-chunks/_stats/store
```

## 남은 과제

lexical/vector hybrid 요구가 있다면 pure vector 결과와 분리해 추가 실험합니다.

## 코드

- `infrastructure/vector/opensearch/OpenSearchVectorStore.java`
- `infrastructure/vector/opensearch/OpenSearchIndexManager.java`
- `infrastructure/vector/opensearch/OpenSearchProperties.java`

## 참고

- [OpenSearch approximate k-NN](https://docs.opensearch.org/latest/vector-search/vector-search-techniques/approximate-knn/)
- [OpenSearch hybrid search](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/index/)
