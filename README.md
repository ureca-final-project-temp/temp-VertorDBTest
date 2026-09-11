# Vector DB 비교·검증 하네스

동일한 BGE-M3 1024차원 벡터와 cosine metric으로 pgvector, Qdrant, Weaviate, Milvus,
OpenSearch의 Recall@10·latency·QPS·자원·인덱스 준비 비용을 비교하는 Spring Boot 하네스입니다.

## 현재 실행과 비교 기준

- 현재 주 비교 행렬은 [data/benchmark-matrix.json](data/benchmark-matrix.json)의 T01~T28입니다.
- 목표 Recall@10은 0.90과 0.95, 허용 범위는 `목표 ±0.01`입니다. 범위에 드는 후보가 없으면 가장 가까운 실제값과 `CLOSEST_AVAILABLE`을 기록합니다.
- 300개 query는 SHA-256 기반 고정 층화 분할로 calibration 100 / evaluation 200이 됩니다. 파라미터 선택은 calibration만, 최종 Recall·p95·QPS는 evaluation만 사용합니다.
- 기본 실행은 인덱스를 포함한 전체 수명주기를 3회 재구축하며 3~5회만 허용합니다. DB 순서는 회차별로 교차합니다.
- 기존 `docs/07-results`의 단일 HNSW 결과는 결함 발견용 역사 자료입니다. 현재 28-case 행렬의 최종 순위표가 아닙니다.
- **2026-09-11 T01~T28 전체 실행을 완료했습니다.** 14개 조합을 3회 재구축하여 인덱스 재구축 42회, 케이스 실행 84회, 기록된 평가 검색 84,000회를 수행했습니다. 현재 결과의 기준은 [이번 전체 실행 보고서](docs/07-results/matrix-results-20260911.md)입니다.
- 이번 결과는 합성 청크 10,000건의 비교입니다. 실제 데이터·100k/1M·별도 필터 선택도 검증은 이번 84회에 포함되지 않았습니다.
- 이번 보고서는 실제 Recall과 목표 충족 횟수, 지연·처리량·자원, 회차 간 변동을 함께 비교합니다. 기존 스크립트의 `eligible` 필드는 제품 선정이나 탈락 판정에 사용하지 않습니다.

## 비교 행렬

| DB | Engine | Index |
|---|---|---|
| pgvector | PostgreSQL | HNSW, IVFFlat |
| Qdrant | Native | HNSW |
| Weaviate | Native | HNSW, HFresh |
| Milvus | Native | HNSW, IVF_FLAT, IVF_SQ8, IVF_PQ, DISKANN |
| OpenSearch | Lucene | HNSW |
| OpenSearch | Faiss | HNSW, IVF |
| OpenSearch | JVector | DiskANN |

각 조합은 Recall 0.90/0.95 두 행을 가집니다. OpenSearch JVector는 KNN 플러그인과 같은 노드에서 공존하지 않으므로 별도 이미지·컨테이너로 실행합니다.

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

- **검색 품질이 다른 상태의 속도를 비교합니다.** ANN은 탐색 폭을 줄이면 빨라질 수 있으므로,
  Recall을 맞추지 않은 latency 비교는 순위를 만들어낼 수 있습니다.
- **자원 조건이 다릅니다.** CPU와 메모리 상한이 다르면 같은 지표를 나란히 둘 수 없습니다.
- **embedding 조건이 다릅니다.** 모델과 차원이 다르면 인덱스 난이도 자체가 달라집니다.

이 저장소는 세 가지를 모두 고정합니다.

| 고정 대상 | 방법 |
|---|---|
| 벡터 | 로컬 Ollama `bge-m3`, dense 1024차원 |
| 거리 | cosine |
| Top-K | 10 |
| Query 분할 | calibration 100 / evaluation 200, query type 층화 + SHA-256 |
| 부하 | concurrency 10, warm-up 1, measurement 5 |
| 자원 | DB 대상 합계 4 vCPU / 8 GiB / swap 없음 |
| 반복 | 3~5회, 매회 drop → create → load → index ready → warm-up → measurement |
| 순서 | 회차별 DB 순서 교차 |
| QPS | 검색 future가 모두 끝난 시점에 타이머 종료. Recall 후처리 제외 |
| Ground truth | 같은 입력에 대한 application exact cosine top-K |

Milvus의 4 vCPU/8 GiB는 Milvus·etcd·MinIO 합계입니다. 실행기는 `docker inspect`로 실제 제한을 확인하고 다르면 측정을 중단합니다. Ollama는 임베딩 생성용이며 벤치마크 종료 시 중단하지 않습니다.

## 실행

요구 사항은 Java 21, Docker Desktop, PowerShell입니다. 임베딩 파일이 이미 있으면 다시 생성하지 않습니다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 `
  -Repetitions 3 `
  -ResultDirectory benchmark-result/matrix-primary
```

일부 case만 검증할 수 있습니다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -TestIds T03,T04,T09,T10 `
  -Repetitions 3 `
  -ResultDirectory benchmark-result/adapter-smoke
```

스크립트는 같은 result directory의 CSV schema가 다르면 중단합니다. 변경된 하네스 결과를 과거 CSV에 이어 쓰지 마세요.

## 필터 선택도, 규모, 실제 데이터

1%/10%/50% 선택도는 기존 tenant 분포를 임의 해석하지 않고 결정적 cohort를 생성합니다.

```powershell
.\scripts\run-filter-selectivity-benchmarks.ps1 `
  -TestIds T02,T06,T08 `
  -Repetitions 3
```

아래 명령은 후속 검증용이며 이번 결과에 포함되지 않습니다. 100k 또는 1M 검증에는 실제 벡터 파일을 명시해야 합니다. 예제의 TestIds는 제품 선정 결과가 아닙니다. 최소 건수 미달이면 실행하지 않습니다.

```powershell
.\scripts\run-shortlist-scale-validation.ps1 `
  -TestIds T02,T06,T08 `
  -Scale 100000 `
  -DocumentVectors D:\dataset\documents-100k.jsonl `
  -QueryVectors D:\dataset\queries-vectors.jsonl `
  -QueryDefinitions D:\dataset\queries.jsonl
```

실제 프로젝트 FAQ/chunk/query 검증은 `synthetic:true`가 문서나 query에 하나라도 있으면 거부합니다.

```powershell
.\scripts\run-real-workload-validation.ps1 `
  -TestIds T02,T06,T08 `
  -DocumentVectors D:\project-data\documents.jsonl `
  -QueryVectors D:\project-data\queries-vectors.jsonl `
  -QueryDefinitions D:\project-data\queries.jsonl
```

## 결과

원시 JSON/CSV에는 다음을 기록합니다.

- test ID, run number, DB/engine/index, 목표·실제 Recall@10
- calibration 선택 방식·Recall, 실제 search parameter
- p50/p95/p99, 순수 검색 QPS
- CPU Avg/Max, RAM Avg/Max, disk write
- index size(제품 API로 분리 측정 가능한 경우), time-to-index-ready, upsert time
- Milvus serial/concurrency Recall 표본과 진단 전후 index/load/query-segment 상태
- 입력 SHA-256, calibration/evaluation query ID SHA-256, Docker 제한

`summary/vector-db-summary.csv`는 Recall 평균/범위, median p95/QPS/자원/구축 시간을 냅니다. 각 실행의 `targetMet`과 평균에 대한 `within_target_tolerance`를 구분합니다. 현재 보고서는 0.90/0.95 목표를 각각 비교하며, 미충족 실행도 실제 측정값과 함께 남깁니다.

스크립트는 과거 집계 규칙의 `eligible`과 `passes_*`를 호환·기록용 필드로 계속 계산합니다. 평균 Recall 0.95, p95 중앙값 30ms, peak RAM 중앙값 2GiB 등의 임계값은 이 비교 프로토콜의 판정 조건으로 사용하지 않습니다. 계산식과 해석 한계는 [비교 결과 해석 기준](docs/07-results/decision.md)에 있습니다.

Milvus에는 추가 안정성 진단이 적용됩니다. 다른 DB의 `stability_verified=true`는 같은 진단을 수행했다는 뜻이 아니므로, 모든 조합의 실제 Recall 변동을 별도로 확인합니다. 제품 결정에 필요한 서비스 요구사항과 실제 데이터 검증은 이번 벤치마크와 구분합니다.

## 문서

- [이번 T01~T28 전체 실행 보고서](docs/07-results/matrix-results-20260911.md)
- [현재 실험 프로토콜](docs/03-benchmark-design/current-protocol.md)
- [실행 방법](docs/04-quickstart/run-benchmark.md)
- [결과 형식](docs/06-implementation/result-format.md)
- [DB별 구현](docs/05-databases/)
- [과거 결과와 결함 분석](docs/07-results/)
- [고위험 adapter 3회 수명주기 스모크](docs/07-results/adapter-smoke-20260911.md)
- [비교 결과 해석 기준](docs/07-results/decision.md)
