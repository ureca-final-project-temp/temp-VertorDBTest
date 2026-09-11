# 벤치마크 실행

목적은 전체 검색 파라미터의 Recall–Latency 산포도입니다. [현재 프로토콜](../03-benchmark-design/current-protocol.md)을 기준으로 하며, 과거 84개 결과는 새 sweep 실험과 구분합니다.

## 전체 sweep

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 `
  -Repetitions 3 `
  -MinimumMeasurementTimeMs 5000 `
  -ResultDirectory benchmark-result/sweep-primary
```

14개 구성의 124개 파라미터를 재구축마다 측정합니다. 기본 3회 실행은 총 42회 재구축·372개 점입니다. [이번 완료 실행](../07-results/sweep-results-20260911.md)에서도 이 개수를 확인했습니다. `Repetitions`는 3~5이며 회차별 DB 순서를 교차합니다. 종료 시 벤치마크 DB를 중지하고 Ollama는 유지합니다.

한 인덱스의 모든 파라미터만 실행할 수도 있습니다.

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -TestIds T03,T09 `
  -Repetitions 3 `
  -ResultDirectory benchmark-result/sweep-subset
```

과거 T02/T04 등 목표별 짝수 행은 제거했습니다. 현재 ID는 T01, T03, …, T27입니다. 새 실험마다 새 결과 디렉터리를 사용합니다.

## 직접 API 호출

DB 프로필과 데이터 경로를 설정해 실행한 애플리케이션의 `POST /api/benchmarks/run`에 다음 본문을 보냅니다. 검색 파라미터 이름은 활성 어댑터가 정합니다.

```json
{
  "rebuildAndLoad": true,
  "scenarios": [{
    "topK": 10,
    "concurrency": 10,
    "warmupIterations": 1,
    "measurementIterations": 5,
    "repetitions": 3,
    "searchParameterValues": [16, 32, 64, 96, 128],
    "searchParameters": {}
  }]
}
```

이 호출은 같은 인덱스에서 5개 파라미터를 3회씩, 총 15개 점으로 측정합니다. API의 `repetitions`는 재구축 횟수가 아닙니다. 외부 실행 스크립트는 `repetitions=1`로 호출하며 재구축을 직접 반복합니다. 그리드 생략 시 `benchmark.search-parameter-values`를 사용합니다. IVF 등에는 어댑터 범위에 맞는 그리드를 명시합니다.

고정 파라미터만 반복하려면 `searchParameterValues`를 생략하고 `searchParameters`에 실제 어댑터의 키와 값을 넣습니다. 두 방식을 동시에 지정하면 오류입니다.

## 저장된 이번 결과 확인

새 테스트를 돌리지 않고 확인하려면 [372개 산포도](../07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.svg)와 [실행 근거 안내](../07-results/assets/sweep-20260911-220549/README.md)를 엽니다. 로컬 실행 디렉터리는 `benchmark-result/sweep-20260911-220549/`입니다. 이 완료 디렉터리를 재실행 출력으로 재사용하지 않습니다.

## 결과 확인

`charts/recall-latency-latest.svg`를 열면 X=p95(ms), Y=Recall@10 산포도가 나옵니다. 모든 반복의 점과 0.90·0.95 수평 참고선이 표시됩니다.

```powershell
Import-Csv benchmark-result/sweep-primary/summary/vector-db-summary.csv |
  Select-Object database,engine,index,search_parameters,completed_measurements,
    recall_mean,recall_min,recall_max,p95_ms_median,qps_median,
    cpu_average_percent_mean,ram_max_bytes_median |
  Format-Table -AutoSize
```

위 `Import-Csv` 예시는 실행 완료 뒤 사용합니다. Windows에서 갱신 중인 CSV를 읽을 때는 파일 교체를 막지 않도록 `FileShare.ReadWrite | FileShare.Delete`로 읽는 방식을 사용합니다. 이번 실행의 안전한 진행 확인 예시는 로컬 `provenance/progress.ps1`에 있습니다.

원시 행은 `csv/vector-db-result.csv`, 측정별 JSON은 `raw/`, 오류는 `failures/`, 로그는 `logs/`입니다. 중간 오류 전까지 완료한 점은 이미 저장되어 있습니다. 동일 파라미터의 반복을 집계하며 Recall 값 때문에 행을 제거하지 않습니다.

## 과거 측정 다시 그리기

```powershell
.\gradlew.bat renderBenchmarkChart `
  '-PchartInput=benchmark-result/matrix-t01-t28-20260911-183751' `
  '-PchartOutput=benchmark-result/historical-replot.svg'
```

새 출력 파일 경로를 지정해야 합니다. 과거 JSON의 실제 Recall과 p95를 그대로 사용하며 원본이나 기존 그림을 덮어쓰지 않습니다. 이 작업은 새 DB 측정이 아닙니다.

## 추가 workload

필터 선택도 스크립트는 같은 임베딩에 1%/10%/50% cohort를 적용합니다.

```powershell
.\scripts\run-filter-selectivity-benchmarks.ps1 -TestIds T01,T05,T07 -Repetitions 3
```

100k/1M 입력은 실제 벡터 파일을 준비해 `run-shortlist-scale-validation.ps1`에 전달합니다. non-synthetic 입력은 `run-real-workload-validation.ps1`로 검사합니다. 두 스크립트도 현재 홀수 구성 ID와 같은 sweep 프로토콜을 사용합니다. 기존 10k 결과가 확장 실험을 대신하지 않습니다.

CPU/RAM -1은 수집 불가, 집계의 null은 유효 표본 없음입니다. Milvus의 안정성 진단은 참고 정보이며 결과 포함 여부를 결정하지 않습니다. [결과 형식](../06-implementation/result-format.md)에 통계의 모집단과 단위를 설명합니다.
