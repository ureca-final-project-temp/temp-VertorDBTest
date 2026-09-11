# Vector DB 비교 벤치마크

동일한 사전 생성 벡터와 질의를 pgvector, Qdrant, Weaviate, Milvus, OpenSearch에 넣고 HNSW의 Recall@K, p50/p95/p99, QPS, CPU, 메모리, 디스크 쓰기량을 비교하는 Spring Boot 프로젝트다. LLM 및 임베딩 생성 시간은 측정 구간에서 제외한다.

## 구현 범위

```text
고정 JSONL 벡터 ──> VectorStore Port ──> DB별 Adapter
        │                                  │
        └─> Java Exact Search ──> Ground Truth
                                      │
고정 Query Set ────────────────────────┴─> Recall/Latency/QPS/Resource
                                               │
                                               └─> JSON + CSV + SVG
```

- `VectorStore`와 `VectorIndexManager` 뒤에 5개 DB 구현을 분리했다.
- Exact Top-K는 DB 결과가 아니라 Java brute-force 계산으로 생성한다.
- 빈 `searchParameters`는 실제 측정과 같은 동시성으로 후보를 평가해 목표 Recall을 만족하는 가장 작은 탐색값을 자동 선택한다.
- 측정 타이머는 `VectorStore.search()` 호출만 감싼다. Controller, 임베딩, Ground Truth, 튜닝 시간은 제외한다.
- PostgreSQL에는 원본 문서/청크용 Flyway 스키마와 Spring Data JDBC 저장 계층이 있다.

## 고정 버전

| 구성 | 버전 |
|---|---:|
| Java | 21 |
| Spring Boot | 4.1.1 |
| PostgreSQL / pgvector | PostgreSQL 17 / pgvector 0.8.6 |
| Qdrant | 1.19.0 |
| Weaviate | 1.39.3 |
| Milvus | 3.0.1 |
| OpenSearch | 3.8.0 |

이미지 태그는 `docker-compose.yml`의 환경변수로 바꿀 수 있다. 비교 실행 중에는 버전을 바꾸지 않는다.

## 입력 파일

실제 데이터의 BGE-M3 dense dimension은 [data/manifest.json](data/manifest.json)의 `1024`다. 원본 10,000개 문서와 300개 질의를 로컬 Ollama의 `bge-m3:latest`로 임베딩했으며 생성 조건과 SHA-256은 [data/embeddings/embedding-manifest.json](data/embeddings/embedding-manifest.json)에 고정돼 있다.

```powershell
.\gradlew.bat generateEmbeddings
```

생성기는 기본 128건 배치, transient 재시도, 실패 배치 자동 분할, checkpoint 재개, 전체 차원·유한값·zero vector 검증을 수행한다. 완료된 출력이 있으면 해시와 레코드 수를 검증하고 재생성하지 않는다. 의도적으로 다시 만들 때만 `-PoverwriteEmbeddings=true`를 사용한다. 이 옵션은 기존 생성 벡터를 교체하므로 모델이나 원본을 바꾼 경우에만 써야 한다.

`document-vectors.jsonl`:

```json
{"id":"chunk-001","documentId":"doc-001","chunkId":"chunk-001","content":"...","embedding":[0.1,0.2],"metadata":{"tenant_id":"alpha","status":"active"}}
```

`queries.jsonl`:

```json
{"queryId":"q-001","query":"...","filter":{"tenant_id":"alpha","status":"active"}}
```

`query-vectors.jsonl`:

```json
{"queryId":"q-001","embedding":[0.1,0.2]}
```

벡터 차원, 정규화 방식, distance metric을 모든 DB에서 반드시 같게 유지한다. 실험용 파일 기본 위치는 다음과 같다.

```text
data/embeddings/document-vectors.jsonl
data/embeddings/query-vectors.jsonl
data/queries/queries.jsonl
```

## 4차원 샘플로 빠르게 확인

PowerShell 기준이다. `pgvector` 대신 `qdrant`, `weaviate`, `milvus`, `opensearch` 중 하나를 넣는다. 독립 Vector DB 프로필에서도 Source of Truth용 PostgreSQL이 함께 필요하다.

```powershell
docker compose --profile qdrant up -d

$env:VECTOR_DIMENSION = "4"
$env:DOCUMENT_VECTORS = "data/sample/documents-vectors.jsonl"
$env:QUERY_DEFINITIONS = "data/sample/queries.jsonl"
$env:QUERY_VECTORS = "data/sample/query-vectors.jsonl"
$env:BENCHMARK_RESULT_DIR = "benchmark-result/qdrant"

.\gradlew.bat bootRun --args="--spring.profiles.active=qdrant"
```

다른 PowerShell 창에서 실행한다.

```powershell
$body = Get-Content -Raw data/sample/benchmark-request.json
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/benchmarks/run `
  -ContentType application/json `
  -Body $body
```

샘플은 연결·적재·검색·필터·결과 저장을 확인하는 smoke test일 뿐이다. 데이터가 너무 작고 4차원으로 인위적이므로 DB 성능 결론에 사용하면 안 된다.

## 실제 벤치마크

1. `embedding-manifest.json`의 모델 digest와 입력·출력 SHA-256을 확인한다.
2. 비교 대상 하나만 시작한다: `docker compose --profile <db> up -d`.
3. 해당 Spring 프로필을 시작하고 같은 요청을 보낸다.
4. 앱과 DB의 캐시 조건을 통일한 뒤 다음 DB로 반복한다.
5. 모든 실행에서 같은 CPU/RAM 제한, Top-K, warm-up, concurrency, 반복 횟수를 유지한다.

제공한 본실험 시나리오는 Target Recall@10 `0.80 / 0.90 / 0.95`(각 `±0.01`), concurrency 10, warm-up 1회, 측정 5회이며 DB별 4,500건의 측정 요청을 만든다. 다섯 DB를 순서대로 실행하고 각 DB를 종료하려면 다음 명령을 사용한다.

```powershell
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1
```

기본 Vector DB 배포 예산은 대상별 합계 `4 vCPU / 8 GiB`다. pgvector, Qdrant, Weaviate, OpenSearch는 단일 컨테이너에 전부 적용하고, Milvus는 본체 `3 vCPU / 6656 MiB`, etcd `0.5 vCPU / 512 MiB`, MinIO `0.5 vCPU / 1 GiB`로 나눈다. `memswap_limit`을 메모리 상한과 같게 설정해 swap 사용도 막는다. 스크립트는 DB 시작 직후 `docker inspect`의 CPU·메모리·swap 합계가 선언 예산과 정확히 같은지 검사하며, 다르면 측정을 시작하지 않는다. 전용 Vector DB 실행에도 필요한 PostgreSQL은 공통 원본 저장소로서 Vector DB 검색 리소스 합계에서는 제외한다.

예산을 바꿀 때는 숫자를 명시한다. Milvus 보조 서비스 몫을 제외한 나머지는 스크립트가 본체에 자동 할당한다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -DatabaseCpuLimit 4.0 `
  -DatabaseMemoryLimitBytes 8589934592
```

일부 DB만 실행할 수도 있다.

```powershell
.\scripts\run-all-benchmarks.ps1 -Profiles pgvector,qdrant
```

0.70과 0.99는 본 비교표에 섞지 않고 필요할 때 별도 보조 실험으로 실행한다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -RequestFile data/benchmark-request-auxiliary.json `
  -ResultDirectory benchmark-result/auxiliary
```

프로필과 자동 조절 파라미터는 다음과 같다.

| 프로필 | 검색 파라미터 | 비고 |
|---|---|---|
| `pgvector` | `ef_search` | JDBC, benchmark에서는 planner의 순차 스캔을 끔 |
| `qdrant` | `hnsw_ef` | REST query API |
| `weaviate` | `ef` | 클래스 HNSW 설정 갱신 |
| `milvus` | `ef` | flush 후 index 완료까지 대기 |
| `opensearch` | `ef_search` | k-NN `method_parameters` |

`searchParameters: {}`이면 `[10,20,40,80,120,200,400,800,1000]` 전체를 본 측정과 같은 warm-up, 동시성, 반복 횟수로 시험한다. 같은 실행 조건을 가진 여러 목표는 이 후보 측정값을 공유한다. 허용 범위에 들어오는 후보가 있으면 가장 작은 검색 파라미터를 선택한다. 없으면 목표와 튜닝 Recall의 절대 차이가 가장 작은 후보를 선택하고 `recallSelection=CLOSEST_AVAILABLE`로 기록한다. 명시값을 쓰려면 예를 들어 pgvector 시나리오에 `"searchParameters":{"ef_search":120}`을 넣는다.

pgvector 프로필은 작은 데이터에서도 HNSW 실험이 순차 검색으로 바뀌지 않도록 `enable_seqscan=off`를 검색 세션에 설정한다. 운영 쿼리 계획을 그대로 재현하려는 별도 실험에서는 `PGVECTOR_FORCE_INDEX_SCAN=false`로 끄고 실행 계획을 함께 보관한다.

Qdrant는 기본 optimizer 임계값 때문에 일부 작은 segment가 exact scan으로 남지 않도록 `full_scan_threshold`와 `optimizers_config.indexing_threshold`를 모두 10KB로 고정하고, `indexed_vectors_count`가 전체 건수에 도달할 때까지 기다린다.

Weaviate는 필터 속성을 스키마 생성 시 선언해야 한다. 제공 데이터의 기본 필드는 `application-weaviate.yml`에 넣었으며 다른 메타데이터 키를 사용하면 `vector.weaviate.filter-fields.<이름>=<Weaviate 타입>`을 추가한다.

## 결과 읽기

```text
benchmark-result/
├─ raw/ground-truth-top10.jsonl
├─ raw/benchmark-<run-id>.json
├─ csv/vector-db-result.csv
└─ charts/recall-latency-latest.svg
```

- `time_to_index_ready_ms`: drop/create, 적재, 비동기 인덱싱 완료 대기를 합친 검색 준비 시간이다. 제품 간 순수 index build 단계가 동일하지 않아 이 정의로 통일했다.
- `upsert_ms`: 위 시간에 포함되는 벡터 적재 구간이다. 한 실행의 첫 시나리오에만 기록한다.
- `cpu_percent`: 대상 컨테이너 CPU의 평균 합계다. Milvus는 Milvus/etcd/MinIO를 합산한다.
- `peak_memory_bytes`: 같은 대상 컨테이너 메모리 합계의 최대값이다.
- `disk_write_bytes`: 검색 측정 중 Docker Block I/O write 증가량이다. 전체 볼륨 크기와 다른 값이다.
- `index_size_bytes`: DB가 직접 제공하는 범위에서 기록하며 미지원은 `-1`이다.
- CSV와 JSON에는 `recall_tolerance`, 최종 측정의 허용 범위 충족 여부인 `target_met`/`targetMet`, 선택 방식인 `recall_selection`, 튜닝 시 실제값인 `tuning_recall`, HNSW 생성·검색 파라미터가 모두 남는다.
- 입력 3개 파일의 SHA-256, OS/CPU/RAM, Java/Spring Boot/Docker 버전, 실제 컨테이너 CPU·메모리 제한도 `environment`에 기록한다.
- SVG는 같은 result directory의 모든 raw 실행을 모아 DB별 색상으로 그린다.

리소스 값이 `-1`이면 Docker 소켓 권한 또는 `docker stats` 실행 가능 여부부터 확인한다. 수십 ms짜리 smoke run의 자원 수치는 대표성이 없다.

## 검색 API

```http
GET /api/search/store
POST /api/search
POST /api/benchmarks/run
GET /actuator/health
```

검색 요청 예:

```json
{
  "vector": [0.0, 0.0, 1.0, 0.0],
  "topK": 3,
  "filter": {"tenant": "public"},
  "searchParameters": {"hnsw_ef": 80}
}
```

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
docker compose --profile qdrant --profile weaviate --profile milvus --profile opensearch config --quiet
```

DB 선정 판단 기준과 운영상 주의점은 [docs/vector-db-selection-guide.md](docs/vector-db-selection-guide.md)를 본다.
실제 BGE-M3 데이터로 수행한 1차 본실험 수치와 판정은 [docs/benchmark-report-20260911.md](docs/benchmark-report-20260911.md)에 정리돼 있다.
