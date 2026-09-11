# 벤치마크 실행

2026-09-11의 T01~T28 전체 84회 실행은 완료했습니다. 결과를 읽으려면 [이번 전체 실행 보고서](../07-results/matrix-results-20260911.md)를 확인합니다. 아래 명령은 새 결과 디렉터리에서 후속 실행할 때 사용합니다.

## 기본 28-case 실행

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 `
  -Repetitions 3 `
  -ResultDirectory benchmark-result/matrix-primary
```

`Repetitions`는 3~5만 허용합니다. 스크립트가 DB별 실행 순서를 교차하고 각 회차마다 전체 인덱스 수명주기를 다시 수행합니다. 마지막에는 벤치마크 DB 컨테이너를 중지하며 Ollama는 중지하지 않습니다.

일부 case만 실행하려면 ID를 고릅니다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -TestIds T03,T04,T09,T10 `
  -Repetitions 3 `
  -ResultDirectory benchmark-result/smoke
```

## 결과 확인

```powershell
Import-Csv benchmark-result/matrix-primary/summary/vector-db-summary.csv |
  Select-Object test_id,database,engine,index,target_recall,completed_runs,
    recall_average,recall_min,recall_max,median_p95_ms,median_qps,
    median_ram_max_bytes,rebuild_recall_range |
  Format-Table -AutoSize
```

원시 행은 `csv/vector-db-result.csv`, 실행별 JSON은 `raw/`, 애플리케이션 로그는 `logs/`, 교차 순서는 `execution-order.json`에 있습니다.

개별 실행의 목표 충족 여부도 확인합니다. 집계 평균이 목표 범위 안이어도 모든 회차가 같은 범위를 만족한 것은 아닙니다.

```powershell
Import-Csv benchmark-result/matrix-primary/csv/vector-db-result.csv |
  Select-Object test_id,run_number,target_recall,comparison_recall,target_met,
    calibration_recall,calibration_selection,search_parameters |
  Format-Table -AutoSize
```

## 필터 선택도

별도 1%/10%/50% 선택도 실험은 이번 84회 결과에 포함되지 않았습니다. 다음 TestIds는 명령 사용 예이며 제품 선정 결과가 아닙니다.

```powershell
.\scripts\run-filter-selectivity-benchmarks.ps1 `
  -TestIds T02,T06,T08 `
  -Repetitions 3
```

생성기는 임베딩을 바꾸지 않고 문서 ID SHA-256 순서로 중첩 cohort를 만들며, 10k에서는 정확히 100/1,000/5,000개 문서를 남깁니다. 세 결과 디렉터리의 `filtered_*`를 비교합니다.

## 100k/1M 규모 검증

실제 100k/1M 입력 검증은 아직 이번 결과로 제시할 수 없습니다. 아래 명령은 후속 실행용입니다. 스크립트 이름의 shortlist가 이번 보고서에서 제품을 선정했다는 뜻은 아닙니다.

```powershell
.\scripts\run-shortlist-scale-validation.ps1 `
  -TestIds T02,T06,T08 `
  -Scale 100000 `
  -DocumentVectors D:\dataset\documents-100k.jsonl `
  -QueryVectors D:\dataset\query-vectors.jsonl `
  -QueryDefinitions D:\dataset\queries.jsonl
```

요청 Scale보다 벡터가 적으면 즉시 실패합니다. 10k 행을 복제한 파일은 유효한 규모 검증이 아닙니다.

## 실제 프로젝트 데이터

non-synthetic 실제 프로젝트 입력 검증도 이번 84회에 포함되지 않았습니다.

```powershell
.\scripts\run-real-workload-validation.ps1 `
  -TestIds T02,T06,T08 `
  -DocumentVectors D:\project\documents.jsonl `
  -QueryVectors D:\project\query-vectors.jsonl `
  -QueryDefinitions D:\project\queries.jsonl
```

문서 metadata나 query 정의에 `synthetic:true`가 발견되면 실행을 거부합니다. 실제 query 길이·유형·metadata 분포를 입력 파일에서 보존해야 합니다.

## 판독 규칙

- `calibration_selection`은 파라미터 선택 결과, `target_met`은 evaluation 판정입니다.
- 0.90/0.95 목표 ±0.01의 충족 횟수와 실제 Recall 범위를 각각 비교합니다. 미충족 행도 지우거나 실행 오류로 바꾸지 않습니다.
- 스크립트의 `eligible`·`passes_*`는 기존 집계 규칙을 보존한 legacy 필드입니다. 평균 Recall 0.95·p95 중앙값 30ms·peak RAM 중앙값 2GiB 등의 기본 문턱은 이번 제품 요구사항이 아니며, 이번 보고서는 이 필드로 선정·탈락을 판단하지 않습니다.
- Milvus의 추가 안정성 진단과 다른 DB의 미실시 기본값을 구분합니다. 모든 조합의 회차별 변동을 확인하며 `stability_verified` 하나로 같은 수준의 검증을 주장하지 않습니다.
- index size가 `-1`이면 제품 API에서 순수 인덱스 크기를 분리하지 못한 것이며 0 bytes가 아닙니다.

세부 계산식은 [결과 형식](../06-implementation/result-format.md), 비교 정책은 [결과 해석 기준](../07-results/decision.md)에 있습니다.
