# Decision Guide

과거 단일 실행 숫자는 현재 제품 순위표가 아닙니다. 새 프로토콜의 `summary/vector-db-summary.csv`에서 반복 완료 행만 사용합니다.

## 성능 gate

기본 `eligible`은 다음을 모두 요구합니다.

1. comparison Recall 평균 ≥ 0.95
2. median unfiltered p95 ≤ 30ms
3. median peak RAM ≤ 2GiB
4. 요청한 3~5회 재구축 완료
5. stability 진단 통과

`within_target_tolerance`는 같은 품질점 비교 가능 여부이고 `passes_recall_floor`는 제품 shortlist 하한입니다. calibration에서 범위를 맞췄어도 evaluation `target_met=false`일 수 있습니다.

## 현실성 gate

성능 gate 다음에 다음 결과가 있어야 합니다.

- 1%/10%/50% 실제 선택도별 filtered Recall/p95
- shortlist 2~3개의 실제 100k, 가능하면 1M 결과
- 실제 FAQ/chunk/query 길이·유형 분포의 non-synthetic 결과
- 재구축·장애복구·백업복구와 허용 RPO/RTO

## 운영 결정

| 관점 | 질문 |
|---|---|
| 정합성 | PostgreSQL 원본과 별도 vector store 사이 outbox/retry/reconciliation을 운영할 수 있는가 |
| 복잡도 | 팀이 새 cluster, upgrade, monitoring, backup을 감당할 수 있는가 |
| 복구 | 전체 재색인 시간과 검색 불가 구간이 SLO 안인가 |
| 확장 | 3년 데이터량과 tenant/write/read 비율에서 scale-out이 실제 필요한가 |
| 검색 제품성 | lexical/hybrid/rerank가 필요한가, pure vector 성능과 분리해 검증했는가 |

PostgreSQL 단일 운영으로 gate를 만족한다면 별도 DB의 동기화·복구 비용을 정당화해야 합니다. 성능이 비슷한 후보는 작은 단일 p95가 아니라 위 운영 비용으로 결정합니다.
