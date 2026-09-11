# Weaviate

전용 Vector DB. 검색은 GraphQL, 적재·스키마는 REST를 사용합니다.

## 설정

`src/main/resources/application-weaviate.yml`

```yaml
vector.weaviate:
  base-url: http://localhost:18080
  class-name: BenchmarkChunk
  dimension: 1024
  metric: COSINE
  hnsw-m: 16
  ef-construction: 128
  ef: 100
  filter-fields:
    tenant: text
    tenant_id: text
    status: text
    access_level: text
    language: text
    category: text
```

## 스키마

```json
POST /v1/schema
{
  "class": "BenchmarkChunk",
  "vectorizer": "none",
  "vectorIndexType": "hnsw",
  "vectorIndexConfig": {
    "distance": "cosine", "maxConnections": 16,
    "efConstruction": 128, "ef": 100
  },
  "properties": [
    {"name": "externalId", "dataType": ["text"]},
    {"name": "documentId", "dataType": ["text"]},
    {"name": "chunkId", "dataType": ["text"]},
    {"name": "content", "dataType": ["text"]},
    {"name": "metadataJson", "dataType": ["text"]},
    {"name": "tenant_id", "dataType": ["text"]},
    ...
  ]
}
```

`vectorizer: none`이 중요합니다. Weaviate가 자체 임베딩을 만들지 않고
우리가 넣은 벡터를 그대로 씁니다.

## 검색

```graphql
{Get{BenchmarkChunk(
  nearVector:{vector:[0.021,-0.118,...]},
  limit:10,
  where:{operator:And,operands:[
    {path:["tenant_id"],operator:Equal,valueText:"alpha"},
    {path:["status"],operator:Equal,valueText:"active"}
  ]}
){externalId documentId chunkId _additional{distance}}}}
```

cosine에서는 `score = 1 - distance`로 변환합니다.

## Sharp edges

### 필터 속성을 스키마 생성 시 선언해야 합니다

Weaviate는 선언되지 않은 property로 필터할 수 없습니다.
제공 데이터의 기본 필드는 YAML에 넣어두었으며, 다른 메타데이터 키를 쓰려면 추가합니다.

```yaml
vector.weaviate.filter-fields.<이름>: <Weaviate 타입>
```

선언하지 않은 키로 필터하면 어댑터가 먼저 거부합니다.

```java
throw new IllegalArgumentException("Weaviate filter field is not declared: " + entry.getKey());
```

선언한 타입과 실제 JSONL metadata 타입이 일치해야 합니다.

### `ef`가 요청별이 아니라 클래스 설정입니다

다른 DB는 질의마다 탐색 파라미터를 보낼 수 있지만 Weaviate는 스키마의 `vectorIndexConfig.ef`를 씁니다.
`configureSearch()`가 스키마를 GET → 수정 → PUT 합니다.

즉 **자동 튜닝 중 후보 9개를 시험하면 스키마가 9번 갱신됩니다.**
튜닝 구간의 지연시간에 이 영향이 섞일 수 있습니다.

### 질의 벡터가 GraphQL 문자열로 전송됩니다

1024개 float를 `String.valueOf`로 join해 약 12 KB의 쿼리 문자열을 만듭니다.
이 문자열 생성과 Weaviate의 GraphQL 파싱이 **측정 타이머 안에 있습니다.**

다섯 DB 중 클라이언트 직렬화 비용이 가장 높은 경로입니다.
[../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)를 봅니다.

### object id가 UUID여야 합니다

Qdrant와 같은 이유로 `UUID.nameUUIDFromBytes()`로 변환하고
원본 id는 `externalId` property에 보관합니다.

### index_size_bytes 미지원

`-1`로 기록됩니다.

## 확인

```powershell
Invoke-RestMethod http://localhost:18080/v1/schema/BenchmarkChunk |
  Select-Object class, vectorIndexType, vectorIndexConfig

Invoke-RestMethod -Method Post http://localhost:18080/v1/graphql `
  -ContentType application/json `
  -Body '{"query":"{Aggregate{BenchmarkChunk{meta{count}}}}"}'
```

## 코드

- `infrastructure/vector/weaviate/WeaviateVectorStore.java`
- `infrastructure/vector/weaviate/WeaviateIndexManager.java`
- `infrastructure/vector/weaviate/WeaviateProperties.java`

## 참고

[Weaviate collection and multi-tenancy](https://docs.weaviate.io/weaviate/manage-collections)
