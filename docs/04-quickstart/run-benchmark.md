# 벤치마크 실행

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
    median_ram_max_bytes,stability_verified,eligible |
  Format-Table -AutoSize
```

원시 행은 `csv/vector-db-result.csv`, 실행별 JSON은 `raw/`, 애플리케이션 로그는 `logs/`, 교차 순서는 `execution-order.json`에 있습니다.

## 필터 선택도

```powershell
.\scripts\run-filter-selectivity-benchmarks.ps1 `
  -TestIds T02,T06,T08 `
  -Repetitions 3
```

생성기는 임베딩을 바꾸지 않고 문서 ID SHA-256 순서로 중첩 cohort를 만들며, 10k에서는 정확히 100/1,000/5,000개 문서를 남깁니다. 세 결과 디렉터리의 `filtered_*`를 비교합니다.

## 100k/1M shortlist

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
- 목표 ±0.01과 최종 Recall 하한은 별도입니다.
- 기본 `eligible`은 Recall 평균 ≥0.95, median p95 ≤30ms, median peak RAM ≤2GiB, 반복 완료, stability 통과를 모두 요구합니다.
- index size가 `-1`이면 제품 API에서 순수 인덱스 크기를 분리하지 못한 것이며 0 bytes가 아닙니다.
