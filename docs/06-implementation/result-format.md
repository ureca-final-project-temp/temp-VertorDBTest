# 결과 형식

현재 형식은 `search-parameter-sweep-v1`입니다. 모든 파라미터·반복의 실제 측정을 보존합니다. 결과에 목표 Recall의 합격 여부나 구성의 eligible 판정을 생성하지 않습니다.

## 디렉터리

```text
<result-directory>/
├─ protocol.txt
├─ raw/benchmark-<timestamp>-<uuid>.json
├─ raw/ground-truth-top10.jsonl
├─ csv/vector-db-result.csv
├─ charts/recall-latency-latest.svg
├─ summary/vector-db-summary.json
├─ summary/vector-db-summary.csv
├─ failures/failure-<uuid>.json
├─ logs/
├─ api-response-run-*.json
└─ execution-order.json
```

각 점을 원시 JSON에 먼저 저장한 뒤 전체 원시값에서 CSV·집계·산포도를 다시 생성합니다. 같은 시각의 결과도 UUID로 구분합니다. 출력 파일 갱신은 임시 파일을 통한 교체로 수행합니다. 중간 실행 오류가 앞서 완료한 측정을 지우지 않습니다.

과거 CSV 스키마나 프로토콜 없는 과거 원시 파일이 있으면 새 디렉터리를 요구합니다. 이전 측정값을 새 프로토콜로 덮어쓰지 않습니다.

## 이번 완료 실행의 고정 사본

실행 디렉터리는 `benchmark-result/sweep-20260911-220549/`이고, 공유할 문서 근거는 [고정 산출물 디렉터리](../07-results/assets/sweep-20260911-220549/README.md)에 있습니다. 원시 372개 점, 동일 파라미터 집계 124개 그룹 × 3회, SVG 372개 점을 대조했습니다.

`all-measurements.json`은 점별 원시 JSON을 한 배열로 모은 사본입니다. `run-manifest.json`, `validation.json`, `run-status.json`, 소스 압축 등은 이번 실행을 검증·보존하면서 추가한 근거 파일입니다. 일반 `run-all-benchmarks.ps1`이 이 부가 파일을 자동으로 모두 생성하는 것은 아닙니다.

## 원시 행

| 필드 | 의미 |
|---|---|
| testId, runNumber | 구성 식별자와 반복 번호 |
| database, engine, indexType | 실제 어댑터 |
| actualRecall | evaluation 전체의 scored 검색에 대한 Recall |
| comparisonRecall | 무필터 Recall, 무필터가 없으면 전체 Recall로 대체하는 보조값 |
| searchParameters, indexParameters | 실제 검색 폭과 생성 설정 |
| averageLatencyMs, p50LatencyMs, p95LatencyMs, p99LatencyMs | 전체 evaluation의 요청 latency 통계 |
| qps | 전체 검색 수 / 검색 작업 완료까지 걸린 시간 |
| measurementTimeMs, resourceSamples | 실제 검색 구간의 길이(ms), 그 구간 안에서 수집한 자원 표본 수 |
| filtered, unfiltered | 각 구간의 검색 수·Recall·latency와 빈 정답 검사 |
| averageCpuPercent, peakCpuPercent | 대상 컨테이너 합산 CPU 표본의 평균/최대 |
| averageMemoryBytes, peakMemoryBytes | 대상 컨테이너 합산 RAM 표본의 평균/최대 |
| diskWriteBytes, indexSizeBytes | 표본 사이 디스크 쓰기 증가량과 제품별 인덱스/store 크기; 미지원 -1 |
| indexBuildTimeMs, upsertTimeMs | 재구축부터 준비 완료까지, 적재 API 시간 |
| stabilityDiagnostics | 별도 직렬/동시성 Recall 표본과 상태; 점 포함 여부를 결정하지 않음 |
| environment, measuredAt | 입력/질의 분할 SHA-256, 환경·자원 조건, 측정 시각 |

QPS 분모와 latency에는 Recall 후처리와 결과 직렬화가 포함되지 않습니다. Store 호출 시간은 요청 변환·네트워크·응답 처리 비용을 포함합니다. 산포도는 전체 p95와 전체 actualRecall을 짝지어 표시합니다.

CSV는 동일 지표를 snake_case 52개 컬럼으로 저장합니다. 객체는 인용된 JSON 문자열입니다. 삭제한 필드는 `target_recall`, `recall_tolerance`, `target_met`, `calibration_selection`, `calibration_recall`입니다. Milvus 진단 안의 calibrationRecall은 진단용 첫 동시성 표본이며 목표 선택값이 아닙니다.

## 빈 정답 질의

| 구간별 컬럼 | 의미 |
|---|---|
| *_queries | 전체 검색 수 |
| *_scored_queries | Recall 평균에 들어가는 검색 수 |
| *_empty_ground_truth_queries | Exact 정답이 비어 있는 검색 수 |
| *_empty_ground_truth_violations | 정답이 없는데 행을 반환한 검색 수 |

`*_queries = *_scored_queries + *_empty_ground_truth_queries`입니다. 정답이 비어 있으면 재현할 순위가 없으므로 Recall에 1.0을 더하지 않습니다. 빈 결과 반환 여부를 따로 검사하고 원시 점은 보존합니다.

## 반복 집계

DB/engine/index, 생성 파라미터, **검색 파라미터**, 벡터 수, Top-K, 동시성, 워밍업·질의 반복 수, 데이터 해시·환경이 같은 측정만 한 행에 묶습니다. 원본 동기화의 실행별 시간 정보는 집계 키에서 제외합니다. test ID가 같아도 검색 파라미터가 다르면 다른 집계입니다.

JSON의 `metrics`와 CSV의 지표별 접미사에 다음을 기록합니다.

- samples: 해당 지표의 유효 표본 수
- mean, median, p95, p99, min, max
- sample_variance: n−1로 나눈 표본분산; 표본이 하나면 null

예를 들어 `p95_ms_median`은 각 독립 측정의 p95 latency들에 대한 median입니다. `p95_ms_p95`는 그 p95 값들 사이의 95분위수입니다. 요청을 합쳐 계산한 p95와 다릅니다.

원시 자원값 -1은 수집 불가입니다. 집계에서 유효 표본이 없으면 null(빈 CSV 셀)로 표시하며 원시값은 그대로 남깁니다. `completed_measurements`와 `run_numbers`는 실제 저장된 반복을 보여주며 Recall 통과 횟수가 아닙니다.

## 산포도

모든 실제 점을 X=p95(ms), Y=Recall@10에 표시합니다. 0.90·0.95 수평선은 참고선입니다. 점에 마우스를 올리면 DB/engine/index, 검색·생성 파라미터, 반복 번호와 성능·자원값을 볼 수 있습니다. 겹친 점도 원시 파일과 CSV에 각각 남습니다.

[과거 84개 결과](../07-results/matrix-results-20260911.md)의 스키마와 목표 관련 필드는 역사 자료에만 남습니다. 그 원본을 새 sweep 측정으로 해석하지 않습니다.
