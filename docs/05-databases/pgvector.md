# pgvector

PostgreSQL 확장. 유일하게 JDBC로 접근하며 Source of Truth와 같은 인스턴스에 있습니다.

## 설정

`src/main/resources/application-pgvector.yml`

```yaml
vector.pgvector:
  table: vector_documents
  dimension: 1024
  metric: COSINE
  hnsw-m: 16
  ef-construction: 128
  default-ef-search: 100
  force-index-scan: true
```

## 스키마

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_documents (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    chunk_id TEXT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX vector_documents_hnsw_idx
    ON vector_documents USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);
```

## 검색

```sql
SELECT set_config('hnsw.ef_search', ?, false), set_config('enable_seqscan', ?, false);

SELECT id, document_id, chunk_id, 1 - (embedding <=> ?) AS score
FROM vector_documents
WHERE true AND metadata ->> ? = ?
ORDER BY embedding <=> ?
LIMIT ?;
```

metric별 연산자와 operator class:

| Metric | 연산자 | Operator class |
|---|---|---|
| COSINE | `<=>` | `vector_cosine_ops` |
| DOT | `<#>` | `vector_ip_ops` |
| EUCLIDEAN | `<->` | `vector_l2_ops` |

## Sharp edges

### `enable_seqscan=off`

작은 테이블에서는 planner가 HNSW 인덱스 대신 순차 스캔을 고릅니다.
그러면 HNSW를 측정하려던 실험이 exact scan을 측정하게 됩니다.
검색 세션에 `enable_seqscan=off`를 설정해 막습니다.

운영 쿼리 계획을 그대로 재현하려는 별도 실험에서는 끕니다.

```powershell
$env:PGVECTOR_FORCE_INDEX_SCAN = "false"
```

이때는 실행 계획(`EXPLAIN`)을 함께 보관해야 결과를 해석할 수 있습니다.

### 질의마다 왕복 한 번 추가

세션 파라미터 적용을 위한 `set_config` 쿼리가 매 검색마다 실행되며,
이 왕복은 **측정 타이머 안에 있습니다.** 다른 DB에는 없는 비용입니다.

### metadata에 인덱스가 없습니다

`metadata ->> 'tenant_id' = 'alpha'` 조건에 쓰이는 JSONB 필드에 인덱스가 없습니다.
pgvector의 filtered ANN은 HNSW 인덱스를 순회하며 조건에 맞는 행을 모으는데,
`ef_search` 한도 안에서 10개를 못 채우면 **결과가 10개보다 적게 나오고 Recall이 떨어집니다.**

`filtered_recall`과 `unfiltered_recall`을 비교하면 이 현상이 드러납니다.
필터 선택도별 검증과 iterative scan 설정은 아직 다루지 않았습니다.

### index_size_bytes

`pg_relation_size('vector_documents_hnsw_idx')`로 인덱스 크기를 보고합니다.
다섯 DB 중 OpenSearch와 함께 실제 값을 제공하는 둘 중 하나입니다.

## 확인

```powershell
docker exec vector-postgres psql -U vector -d vectorlab -c `
  "SELECT count(*) FROM vector_documents;"

docker exec vector-postgres psql -U vector -d vectorlab -c `
  "SELECT indexname FROM pg_indexes WHERE tablename='vector_documents';"
```

## 코드

- `infrastructure/vector/pgvector/PgVectorStore.java`
- `infrastructure/vector/pgvector/PgVectorIndexManager.java`
- `infrastructure/vector/pgvector/PgVectorProperties.java`

## 참고

[pgvector HNSW, filtering, iterative scan](https://github.com/pgvector/pgvector)
