# Milvus

전용 Vector DB. standalone 모드에서도 etcd와 MinIO가 필요합니다. REST v2 API를 사용합니다.

## 설정

`src/main/resources/application-milvus.yml`

```yaml
vector.milvus:
  base-url: http://localhost:19530
  token: root:Milvus
  database: default
  collection: benchmark_chunks
  dimension: 1024
  metric: COSINE
  hnsw-m: 16
  ef-construction: 128
  default-ef-search: 100
```

## 컬렉션 생성

```json
POST /v2/vectordb/collections/create
{
  "collectionName": "benchmark_chunks",
  "schema": {
    "autoId": false, "enabledDynamicField": false,
    "fields": [
      {"fieldName":"id","dataType":"VarChar","isPrimary":true,"elementTypeParams":{"max_length":"256"}},
      {"fieldName":"metadata","dataType":"JSON","elementTypeParams":{}},
      {"fieldName":"embedding","dataType":"FloatVector","elementTypeParams":{"dim":"1024"}}
    ]
  },
  "indexParams": [{
    "metricType":"COSINE","fieldName":"embedding","indexName":"embedding_hnsw",
    "indexType":"HNSW","params":{"M":16,"efConstruction":128}
  }]
}
```

## 검색

```json
POST /v2/vectordb/entities/search
{
  "collectionName": "benchmark_chunks",
  "data": [[0.021, -0.118, ...]],
  "annsField": "embedding",
  "limit": 10,
  "outputFields": ["id", "document_id", "chunk_id"],
  "searchParams": {"metricType": "COSINE", "params": {"ef": 80}},
  "filter": "metadata[\"tenant_id\"] == \"alpha\" and metadata[\"status\"] == \"active\""
}
```

## Sharp edges

### flush 없이 측정하면 HNSW가 아닙니다

삽입한 데이터는 먼저 growing segment에 들어갑니다. 이 상태에서는 Milvus가 인덱스가 아니라
brute-force로 검색합니다. `flush`로 segment를 봉인해야 HNSW가 만들어집니다.

`awaitReady()`가 이 장벽을 담당합니다.

```java
requireSuccess(client.post("/v2/vectordb/collections/flush", identity()));
// 이후 indexState=Finished, indexedRows >= 전체, pendingRows == 0 까지 대기
```

이 대기 없이 측정하면 Recall은 1.0에 가깝고 latency는 exact 수준으로 나옵니다.

### 자원 예산을 세 컨테이너가 나눠 씁니다

| 컨테이너 | CPU | 메모리 |
|---|---:|---:|
| `vector-milvus` | 3.0 | 6656 MiB |
| `vector-milvus-etcd` | 0.5 | 512 MiB |
| `vector-milvus-minio` | 0.5 | 1 GiB |
| 합계 | 4.0 | 8 GiB |

다른 DB와 **합계가 같습니다.** 보조 서비스 때문에 예산을 더 주지 않습니다.
CPU·메모리 지표도 세 컨테이너 합산입니다.

### 결과가 비결정적입니다

여러 segment에 걸친 검색이라 같은 파라미터에서도 Recall이 미세하게 흔들립니다.
튜닝 Recall과 본 측정 Recall이 달라질 수 있으므로 선택 표시만 보지 말고 본 측정의
`comparison_recall`과 `target_met`을 함께 확인해야 합니다.

```text
tuning_recall 0.9578  →  comparison_recall 0.7422, target_met=false
```

위 값은 2026-09-11 단일 재실행에서 관측한 큰 변동 사례이며, 모든 실행에서 같은 폭으로
발생한다는 뜻은 아닙니다.

### metadata가 JSON 필드입니다

`metadata`를 JSON 타입으로 저장하고 필터는 `metadata["key"] == "value"` 표현식을 씁니다.
JSON path index를 만들지 않았으므로 필터 평가 비용이 필드 인덱스보다 높습니다.

### index_size_bytes 미지원

`-1`로 기록됩니다.

## 확인

```powershell
Invoke-RestMethod http://localhost:9091/healthz

Invoke-RestMethod -Method Post http://localhost:19530/v2/vectordb/indexes/describe `
  -ContentType application/json `
  -Headers @{Authorization='Bearer root:Milvus'} `
  -Body '{"collectionName":"benchmark_chunks","indexName":"embedding_hnsw"}'
```

`indexState`가 `Finished`, `pendingRows`가 0이어야 합니다.

## 코드

- `infrastructure/vector/milvus/MilvusVectorStore.java`
- `infrastructure/vector/milvus/MilvusIndexManager.java`
- `infrastructure/vector/milvus/MilvusProperties.java`

## 참고

- [Milvus deployment options](https://milvus.io/docs/install-overview.md)
- [Milvus architecture](https://milvus.io/docs/architecture_overview.md)
