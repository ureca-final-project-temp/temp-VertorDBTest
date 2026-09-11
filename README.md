# Vector DB 비교·검증 하네스

동일한 BGE-M3 1024차원 벡터와 cosine metric으로 pgvector, Qdrant, Weaviate, Milvus,
OpenSearch의 Recall@10·latency·QPS·자원·인덱스 준비 비용을 비교하는 Spring Boot 하네스입니다.

## 현재 실행과 비교 기준

핵심 산출물은 **X=p95 latency, Y=Recall@10 산포도**입니다. 각 점은 DB + Engine + Index + 검색 파라미터의 실제 반복 측정입니다. Recall 0.90과 0.95는 수평 참고선이며 PASS/FAIL이나 결과 제거 기준으로 사용하지 않습니다.

- [행렬](data/benchmark-matrix.json)은 14개 구성(T01, T03, …, T27)의 전체 검색 파라미터 그리드를 담습니다. 과거의 목표별 짝수 행은 제거했습니다.
- 새 방식의 [전체 sweep 실험](docs/07-results/sweep-results-20260911.md)을 완료했습니다. 14개 구성을 각각 3회 재구축하고 회차별 124개 파라미터를 측정해 **372개 실측 점**을 저장했습니다.
- evaluation 200개를 각 파라미터에서 동일하게 사용합니다. 기존 calibration 100개는 추가 진단용으로 남기며 파라미터 선택에는 사용하지 않습니다.
- 한 점이 끝날 때마다 JSON·CSV·집계·산포도를 저장합니다. 집계는 검색 파라미터와 데이터·부하 조건이 같은 반복끼리 수행합니다.
- [과거 84개 결과](docs/07-results/matrix-results-20260911.md)는 선택된 파라미터만 측정한 역사 자료입니다. 새 sweep 실험의 완료 결과가 아닙니다.
- 본 측정 검색은 총 **2,948,000회**이며, 측정 시간은 점당 최소 5,001ms, 자원 표본은 최소 2개입니다. 실행 오류 0건이며 0.340206–0.999485의 모든 Recall을 보존했습니다.
- 현재 주 산포도는 새 실험의 [372개 전체 실측 SVG](docs/07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.svg)입니다. 원시값과 좌표 일치, 동일 파라미터별 3회 측정, CPU·RAM 누락 0개를 확인했습니다.

![새 sweep 실험의 전체 372개 실측 점](docs/07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.png)

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

각 조합은 전체 검색 파라미터 그리드를 가진 한 행입니다. OpenSearch JVector는 KNN 플러그인과 같은 노드에서 공존하지 않으므로 별도 이미지·컨테이너로 실행합니다.

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
  실제 Recall을 함께 보지 않은 latency 비교는 품질 차이를 숨길 수 있습니다.
- **자원 조건이 다릅니다.** CPU와 메모리 상한이 다르면 같은 지표를 나란히 둘 수 없습니다.
- **embedding 조건이 다릅니다.** 모델과 차원이 다르면 인덱스 난이도 자체가 달라집니다.

입력과 자원 조건을 고정하고 검색 폭에 따른 품질·성능 변화를 측정합니다.

| 고정 대상 | 방법 |
|---|---|
| 벡터 | 로컬 Ollama `bge-m3`, dense 1024차원 |
| 거리 | cosine |
| Top-K | 10 |
| Query 분할 | calibration 100 / evaluation 200, query type 층화 + SHA-256 |
| 부하 | concurrency 10, warm-up 1, measurement 5, 최소 5초 |
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
  -ResultDirectory benchmark-result/sweep-primary
```

일부 case만 검증할 수 있습니다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -TestIds T03,T09 `
  -Repetitions 3 `
  -ResultDirectory benchmark-result/adapter-smoke
```

새 실험에는 새 결과 디렉터리를 사용합니다. 원시 측정 파일이 있는 디렉터리는 실행 스크립트가 재사용하지 않습니다.

## 필터 선택도, 규모, 실제 데이터

1%/10%/50% 선택도는 기존 tenant 분포를 임의 해석하지 않고 결정적 cohort를 생성합니다.

```powershell
.\scripts\run-filter-selectivity-benchmarks.ps1 `
  -TestIds T01,T05,T07 `
  -Repetitions 3
```

아래 명령은 후속 검증용이며 이번 결과에 포함되지 않습니다. 100k 또는 1M 검증에는 실제 벡터 파일을 명시해야 합니다. 예제의 TestIds는 제품 선정 결과가 아닙니다. 최소 건수 미달이면 실행하지 않습니다.

```powershell
.\scripts\run-shortlist-scale-validation.ps1 `
  -TestIds T01,T05,T07 `
  -Scale 100000 `
  -DocumentVectors D:\dataset\documents-100k.jsonl `
  -QueryVectors D:\dataset\queries-vectors.jsonl `
  -QueryDefinitions D:\dataset\queries.jsonl
```

실제 프로젝트 FAQ/chunk/query 검증은 `synthetic:true`가 문서나 query에 하나라도 있으면 거부합니다.

```powershell
.\scripts\run-real-workload-validation.ps1 `
  -TestIds T01,T05,T07 `
  -DocumentVectors D:\project-data\documents.jsonl `
  -QueryVectors D:\project-data\queries-vectors.jsonl `
  -QueryDefinitions D:\project-data\queries.jsonl
```

## 결과

- 원시 JSON/CSV: 모든 파라미터와 반복의 Recall, 평균/p50/p95/p99 latency, QPS, CPU·RAM 평균/최대, 구축·적재 비용과 입력 해시.
- `charts/recall-latency-latest.svg`: 전체 p95와 전체 Recall을 짝지은 모든 실제 점, 0.90·0.95 참고선, 파라미터와 자원 tooltip.
- `summary/vector-db-summary.json` / `.csv`: 동일 생성·검색 설정과 입력·부하 조건별 평균, median, p95/p99, min/max, 표본분산 및 유효 표본 수.
- `failures/`: 실행 오류 기록. 앞선 측정과 산포도는 남습니다.

집계의 p95/p99는 반복 측정 지표 사이의 분위수입니다. 개별 점의 p95/p99는 해당 점의 검색 요청 사이의 분위수입니다. CPU/RAM 수집 불가는 원시에 -1, 집계에는 null과 표본 수로 남깁니다.

Milvus의 별도 안정성 진단은 참고 정보로 보존하며 산포도 점이나 구성을 제거하지 않습니다. 세부 사항은 [현재 프로토콜](docs/03-benchmark-design/current-protocol.md)과 [결과 형식](docs/06-implementation/result-format.md)을 확인합니다.
## 문서

- [새 sweep 실험 372개 전체 측정 보고서](docs/07-results/sweep-results-20260911.md)
- [문서 읽기 순서와 현재 결과 근거](docs/README.md)
- [과거 목표별 파라미터 선택 방식의 84개 결과](docs/07-results/matrix-results-20260911.md)
- [현재 실험 프로토콜](docs/03-benchmark-design/current-protocol.md)
- [실행 방법](docs/04-quickstart/run-benchmark.md)
- [결과 형식](docs/06-implementation/result-format.md)
- [DB별 구현](docs/05-databases/)
- [과거 결과와 결함 분석](docs/07-results/)
- [고위험 adapter 3회 수명주기 스모크](docs/07-results/adapter-smoke-20260911.md)
- [비교 결과 해석 기준](docs/07-results/decision.md)
