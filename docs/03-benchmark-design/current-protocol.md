# 현재 실험 프로토콜

이 문서가 실험 실행의 기준 문서입니다. 과거 단일 HNSW 결과 문서보다 우선합니다.

## Stage 1 — Harness 검증

1. query type을 보존하는 SHA-256 층화 분할로 calibration 100 / evaluation 200을 고정한다.
2. ANN 파라미터 후보는 calibration의 무필터 query로만 선택한다.
3. warm-up, 최종 Recall, p50/p95/p99, QPS는 evaluation query로만 측정한다.
4. QPS 타이머는 모든 검색 응답 수집 직후 종료하고 Recall 계산은 이후 수행한다.
5. adapter contract test와 실제 DB 수명주기 스모크를 모두 통과시킨다.

## Stage 2 — 통계적 안정성

- 각 index 조합을 3~5회 전체 재구축한다.
- 기본 3회 DB 순서는 `P-Q-W-M-O`, `W-O-P-Q-M`, `M-Q-O-W-P`다.
- 최종 표는 Recall 평균/min/max, median p95, median QPS를 사용한다.
- Milvus는 선택된 같은 파라미터로 calibration query를 serial과 본 concurrency에서 각각 3회 재측정한다. index/load/query-segment 상태를 진단 전후 저장하며, 회차 간 evaluation Recall range도 기본 0.05 이하여야 한다. 어느 진단이든 미통과면 shortlist에서 제외한다.

## Stage 3 — 현실성 검증

- 10k 결과로 2~3개 index 조합만 shortlist한다.
- 실제로 임베딩한 100k 입력으로 확대하고, 자원이 허용되면 1M을 추가한다. 행 복제나 벡터 복제로 규모를 가장하지 않는다.
- 실제 FAQ/chunk metadata, query 길이·유형 분포를 반영한 non-synthetic 입력을 별도 실행한다.
- 필터 query는 실제 1%/10%/50% 선택도를 갖는 세 workload로 분리한다.

## Stage 4 — 최종 shortlist

기본 규칙은 다음과 같다.

- Recall@10 평균 ≥ 0.95
- median unfiltered p95 ≤ 30ms
- median peak RAM ≤ 2GiB
- 요청한 반복 횟수 완료
- stability 진단 통과

필요하면 실행 인자로 문턱을 바꾸되 결과에 기준을 함께 보관한다. 목표 `±0.01`은 같은 품질점 비교를 위한 조건이고, Recall 하한은 제품 shortlist 조건이다.

## Stage 5 — 제품 결정

통과 후보끼리 운영 복잡도, 원본 DB와 벡터 DB 간 정합성, 백업·복구, 관측성, scale-out 필요성을 비교한다. 더 작은 단일 p95만으로 제품을 선정하지 않는다.
