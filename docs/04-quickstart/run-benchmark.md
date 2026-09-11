# Run Benchmark

## 완료 기준

다섯 DB의 측정 결과가 하나의 CSV에 모이고, 각 행의 `recall_selection`과
`target_met`을 함께 확인해 그 행을 비교에 쓸 수 있는지 판단할 수 있습니다.

## 사전 요구사항

[local-setup.md](local-setup.md)를 완료하세요. 특히 다음이 필요합니다.

- `build/libs/VectorDBTest-0.0.1-SNAPSHOT.jar`
- `data/embeddings/document-vectors.jsonl`
- `data/embeddings/query-vectors.jsonl`
- `data/queries/queries.jsonl`

## 전체 실행

```powershell
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 -ResultDirectory benchmark-result/run-01
```

스크립트가 DB마다 수행하는 일:

```text
1. docker compose up   해당 DB + postgres
2. HTTP readiness 대기
3. docker inspect로 CPU·메모리·swap 합계 검증   ← 불일치 시 즉시 중단
4. jar 기동, /actuator/health 대기
5. POST /api/benchmarks/run
6. 결과 요약 출력
7. 앱 종료, DB 컨테이너 stop
```

### 확인

```powershell
Import-Csv benchmark-result/run-01/csv/vector-db-result.csv |
  Select-Object database, target_recall, comparison_recall, target_met, recall_selection,
                unfiltered_p95_ms, filtered_p95_ms, qps | Format-Table -AutoSize
```

15행(5 DB × 3 목표)이 나오면 성공입니다.

## 일부 DB만 실행

```powershell
.\scripts\run-all-benchmarks.ps1 -Profiles pgvector,qdrant
```

## 보조 실험 (0.70 / 0.99)

주 비교표에 섞지 않고 별도 디렉터리에 실행합니다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -RequestFile data/benchmark-request-auxiliary.json `
  -ResultDirectory benchmark-result/auxiliary
```

## 자원 예산 변경

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -DatabaseCpuLimit 4.0 `
  -DatabaseMemoryLimitBytes 8589934592
```

Milvus 보조 서비스 몫을 제외한 나머지가 자동으로 본체에 할당됩니다.

## 수동 실행 (DB 하나)

```powershell
docker compose --profile qdrant up -d postgres qdrant

$env:BENCHMARK_RESULT_DIR = "benchmark-result/manual-qdrant"
.\gradlew.bat bootRun --args="--spring.profiles.active=qdrant --vector.qdrant.dimension=1024"
```

다른 창에서:

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/benchmarks/run `
  -ContentType application/json `
  -Body (Get-Content -Raw data/benchmark-request.json)
```

## 결과 읽기

```text
benchmark-result/run-01/
├─ raw/ground-truth-top10.jsonl      정답지
├─ raw/benchmark-<run-id>.json       실행별 전체 결과
├─ csv/vector-db-result.csv          누적 비교표
├─ charts/recall-latency-latest.svg  unfiltered Recall vs unfiltered p95
└─ logs/                             DB별 애플리케이션 로그
```

읽는 순서:

1. `recall_selection=WITHIN_TOLERANCE`이면서 `target_met=true`인 행만 고릅니다.
2. 같은 `target_recall`끼리 묶습니다.
3. `unfiltered_p95_ms`로 ANN 성능을, `filtered_p95_ms`로 필터 처리 능력을 비교합니다.
4. CPU가 1% 미만인 행은 과소 집계이므로 자원 비교에서 제외합니다.

지표 정의는 [../03-benchmark-design/metrics.md](../03-benchmark-design/metrics.md),
파일 스키마는 [../06-implementation/result-format.md](../06-implementation/result-format.md)에 있습니다.

## 주의

- **CSV 스키마가 바뀌면 기존 디렉터리에 이어 쓸 수 없습니다.**
  `ResultWriter`가 헤더 불일치를 감지하고 새 디렉터리를 쓰라는 예외를 던집니다.
- 한 실행의 `time_to_index_ready_ms`와 `upsert_ms`는 **첫 시나리오에만** 기록됩니다.
- 비교 실행 중에는 이미지 태그를 바꾸지 않습니다.

## 실패했다면

[../08-troubleshooting/common-issues.md](../08-troubleshooting/common-issues.md)를 봅니다.

## 다음 단계

- 결과 해석 → [../07-results/analysis.md](../07-results/analysis.md)
- 선정 판단 → [../07-results/decision.md](../07-results/decision.md)
