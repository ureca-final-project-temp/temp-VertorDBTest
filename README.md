# Vector DB Benchmark

Spring Boot 기반 RAG Retrieval 환경에서
pgvector, Qdrant, Weaviate, Milvus, OpenSearch를 비교합니다.

## 결론 요약

본 실험은 동일한 BGE-M3 embedding, 동일 Dataset, 동일 Query Set,
동일 Top-K 및 동일 Resource 제한 조건에서
각 Vector DB의 ANN 검색 성능을 비교합니다.

Exact Search 결과를 Ground Truth로 사용하고,
ANN Recall@10을 기준으로 검색 품질을 맞춘 뒤
다음 지표를 비교합니다.

- p95 / p99 Latency
- QPS
- CPU
- RAM
- Time-to-ready (drop/create + 적재 + 준비 완료)
- 검색 중 Disk write / 제품이 제공하는 index size

최종 평가는 단순히 "가장 빠른 DB"를 선정하는 것이 아니라,

> 동일한 검색 품질에서 어떤 Vector DB가
> 더 낮은 지연시간과 적은 자원으로 검색을 수행하는가

를 기준으로 합니다.

### 재실행 결과 요약

측정 결함을 수정한 뒤 2026-09-11에 1회 재실행했습니다. 아래 표는 목표 0.95 행의
**무필터 비교 Recall과 무필터 p95**를 사용합니다. Milvus는 튜닝 단계에서는 범위에
들었지만 본 측정 Recall이 크게 변해 직접 비교에서 제외합니다.

| DB | 비교 Recall@10 | 범위 충족 | 무필터 p95 ms | QPS | RAM MiB | 평가 |
|---|---:|:---:|---:|---:|---:|---|
| pgvector | 0.9489 | O | 31.05 | 1,485.78 | 194.6 | 직접 비교 가능 |
| Qdrant | 0.9522 | O | 5.62 | 2,751.52 | 101.3 | 직접 비교 가능 |
| Weaviate | 0.9515 | O | 31.29 | 601.10 | 294.3 | 직접 비교 가능 |
| Milvus | 0.7422 | X | 30.21 | 1,103.26 | 710.9 | 본 측정 drift, 제외 |
| OpenSearch | 0.9493 | O | 16.75 | 1,696.75 | 5,123.1 | 직접 비교 가능 |

상세 결과와 해석은 [docs/07-results/benchmark-results-rerun.md](docs/07-results/benchmark-results-rerun.md),
1차 실행이 무효가 된 경위는 [docs/07-results/analysis.md](docs/07-results/analysis.md)에 있습니다.

## 실험 한눈에 보기

```text
BGE-M3
  → 1024-dimensional vector
  → Same Dataset
  → Same Query Vector
  → Exact Top-K
  → ANN Top-K
  → Recall@10
  → Latency / QPS / CPU / RAM 비교
```

## 왜 비교하는가

RAG 서비스에서 Vector DB를 고를 때 흔히 보는 벤치마크는 세 가지 이유로 그대로 쓰기 어렵습니다.

- **검색 품질이 다른 상태의 속도를 비교합니다.** ANN은 탐색 폭을 줄이면 항상 빨라지므로,
  Recall을 맞추지 않은 latency 비교는 순위를 만들어낼 수 있습니다.
- **자원 조건이 다릅니다.** CPU와 메모리 상한이 다르면 같은 지표를 나란히 둘 수 없습니다.
- **embedding 조건이 다릅니다.** 모델과 차원이 다르면 인덱스 난이도 자체가 달라집니다.

이 저장소는 세 가지를 모두 고정합니다.

| 고정 대상 | 방법 |
|---|---|
| 검색 품질 | Exact Top-K를 Ground Truth로 계산하고, Recall@10이 목표 구간에 들도록 DB별 탐색 파라미터를 자동 조정 |
| 자원 | 대상 합계 4 vCPU / 8 GiB, swap 금지. 측정 전 `docker inspect`로 실제 상한을 검증하고 다르면 중단 |
| 입력 | 동일한 BGE-M3 1024차원 벡터 파일을 전 DB에 재사용하고 SHA-256으로 고정 |
| 원본 | PostgreSQL에 원문 200건·청크 10,000건을 적재하고 입력 SHA-256과 건수를 검증 |

측정 구간은 `VectorStore.search()` 호출만 감쌉니다. LLM 및 embedding 생성 시간은 포함하지 않습니다.

이 벤치마크가 답하지 않는 질문은 [docs/03-benchmark-design/limitations.md](docs/03-benchmark-design/limitations.md)에 있습니다.

## 비교 대상

| DB | 버전 | 인덱스 | 탐색 파라미터 | 접근 방식 |
|---|---|---|---|---|
| pgvector | PostgreSQL 17 / pgvector 0.8.6 | HNSW | `ef_search` | JDBC |
| Qdrant | 1.19.0 | HNSW | `hnsw_ef` | REST query API |
| Weaviate | 1.39.3 | HNSW | `ef` | GraphQL |
| Milvus | 3.0.1 | HNSW | `ef` | REST v2 |
| OpenSearch | 3.8.0 | Lucene HNSW | `ef_search` | REST |

모든 DB가 HNSW를 사용하고 생성 파라미터는 `M=16`, `ef_construction=128`로 같습니다.
거리 함수는 cosine, Top-K는 10입니다.

제품별 설정과 주의점은 [docs/05-databases/](docs/05-databases/)를 봅니다.

## Quick Start

요구사항: Java 21, Docker 27+, PowerShell, 로컬 Ollama(`bge-m3:latest`)

4차원 샘플 데이터로 연결·적재·검색·결과 저장까지 확인하는 경로입니다.
Ollama 없이 바로 실행할 수 있습니다.

```powershell
docker compose --profile qdrant up -d postgres qdrant

$env:VECTOR_DIMENSION = "4"
$env:DOCUMENT_VECTORS = "data/sample/documents-vectors.jsonl"
$env:QUERY_DEFINITIONS = "data/sample/queries.jsonl"
$env:QUERY_VECTORS = "data/sample/query-vectors.jsonl"
$env:BENCHMARK_RESULT_DIR = "benchmark-result/smoke"

.\gradlew.bat bootRun --args="--spring.profiles.active=qdrant"
```

다른 PowerShell 창에서 실행합니다.

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/benchmarks/run `
  -ContentType application/json `
  -Body (Get-Content -Raw data/sample/benchmark-request.json)
```

### 정상 동작 확인

```powershell
Test-Path benchmark-result/smoke/csv/vector-db-result.csv
# expected: True
```

CSV에 `database=qdrant` 행이 1개 이상 있고 `actual_recall`이 0보다 크면 성공입니다.

> 샘플은 배선 확인용 smoke test입니다. 4차원 20건짜리 인위적 데이터이므로
> **DB 성능 결론에 사용하면 안 됩니다.**

본실험 실행은 [docs/04-quickstart/run-benchmark.md](docs/04-quickstart/run-benchmark.md)를 봅니다.

## Documentation

| 목적 | 문서 |
|---|---|
| 시스템 구조와 측정 경로 | [docs/01-overview/architecture.md](docs/01-overview/architecture.md) |
| 실험이 무엇이고 무엇이 아닌가 | [docs/01-overview/benchmark-overview.md](docs/01-overview/benchmark-overview.md) |
| 벡터·임베딩·HNSW·Recall 개념 | [docs/02-concepts/](docs/02-concepts/) |
| 실험 설계와 통제 변수 | [docs/03-benchmark-design/](docs/03-benchmark-design/) |
| 지표 정의와 읽는 법 | [docs/03-benchmark-design/metrics.md](docs/03-benchmark-design/metrics.md) |
| 이 실험의 한계 | [docs/03-benchmark-design/limitations.md](docs/03-benchmark-design/limitations.md) |
| 로컬 설치와 실행 | [docs/04-quickstart/](docs/04-quickstart/) |
| DB별 설정과 주의점 | [docs/05-databases/](docs/05-databases/) |
| 코드 구조와 결과 파일 스키마 | [docs/06-implementation/](docs/06-implementation/) |
| 측정 결과와 판정 | [docs/07-results/](docs/07-results/) |
| 자주 겪는 문제 | [docs/08-troubleshooting/common-issues.md](docs/08-troubleshooting/common-issues.md) |

## Status

측정 결함 수정 후 1회 재실행을 완료했습니다. 최종 선정 전 반복 실행은 남아 있습니다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
docker compose --profile qdrant --profile weaviate --profile milvus --profile opensearch config --quiet
```

| 항목 | 상태 |
|---|---|
| 5개 DB 어댑터 | 구현 완료 |
| 자동 Recall 튜닝 | 구현 완료 |
| 1차 본실험 | 완료, 측정 결함으로 무효 |
| 결함 수정 후 재실행 | 1회 완료 |
| 반복 재구축 검증 | 대기 중 |
