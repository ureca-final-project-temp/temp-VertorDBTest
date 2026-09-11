# Vector DB 비교·검증 하네스

동일한 BGE-M3 1024차원 벡터와 cosine metric으로 pgvector, Qdrant, Weaviate, Milvus,
OpenSearch의 Recall@10·latency·QPS·자원·인덱스 준비 비용을 비교하는 Spring Boot 하네스입니다.

## 현재 판정 상태

- 현재 주 비교 행렬은 [data/benchmark-matrix.json](data/benchmark-matrix.json)의 T01~T28입니다.
- 목표 Recall@10은 0.90과 0.95, 허용 범위는 `목표 ±0.01`입니다. 범위에 드는 후보가 없으면 가장 가까운 실제값과 `CLOSEST_AVAILABLE`을 기록합니다.
- 300개 query는 SHA-256 기반 고정 층화 분할로 calibration 100 / evaluation 200이 됩니다. 파라미터 선택은 calibration만, 최종 Recall·p95·QPS는 evaluation만 사용합니다.
- 기본 실행은 인덱스를 포함한 전체 수명주기를 3회 재구축하며 3~5회만 허용합니다. DB 순서는 회차별로 교차합니다.
- 기존 `docs/07-results`의 단일 HNSW 결과는 결함 발견용 역사 자료입니다. 현재 28-case 행렬의 최종 순위표가 아닙니다.
- HFresh, pgvector IVFFlat, OpenSearch Faiss IVF/JVector DiskANN, Milvus DISKANN의 3회 수명주기 스모크는 완료했습니다. 전체 T01~T28 공식 실행과 실제 데이터·100k 입력 검증은 별도 결과 디렉터리에서 수행해야 합니다.

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

## 공정성 계약
| 항목 | 고정값 또는 검증 방식 |
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

<img width="986" height="512" alt="image" src="https://github.com/user-attachments/assets/7182ad53-7801-4338-aaa5-d9237652068a" />


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

10k에서 shortlist한 2~3개 조합의 100k 또는 1M 검증은 실제 벡터 파일을 명시해야 합니다. 최소 건수 미달이면 실행하지 않습니다.

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

`summary/vector-db-summary.csv`는 Recall 평균/범위, median p95/QPS/자원/구축 시간을 냅니다. 기본 최종 의사결정 규칙은 `Recall ≥ 0.95`, `median p95 ≤ 30ms`, `median RAM max ≤ 2GiB`, 반복 완료, stability 진단 통과입니다. `within_target_tolerance`와 최종 `eligible`은 다른 개념입니다.

성능 조건을 통과한 후보끼리는 운영 복잡도, 원본 데이터와 벡터의 정합성, 장애 복구, scale-out 필요성으로 결정합니다. 단일 latency 점수로 제품을 고르지 않습니다.

## 문서

- [현재 실험 프로토콜](docs/03-benchmark-design/current-protocol.md)
- [실행 방법](docs/04-quickstart/run-benchmark.md)
- [결과 형식](docs/06-implementation/result-format.md)
- [DB별 구현](docs/05-databases/)
- [과거 결과와 결함 분석](docs/07-results/)
- [고위험 adapter 3회 수명주기 스모크](docs/07-results/adapter-smoke-20260911.md)
