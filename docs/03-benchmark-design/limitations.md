# Limitations

## 아직 결과가 없는 축

현재 저장소에는 합성 10k embedding만 있습니다. 다음은 실행 코드와 fail-fast guard는 있으나 유효 입력이 없어 최종 수치가 없습니다.

- 실제로 임베딩한 100k/1M 데이터
- 실제 프로젝트 FAQ/chunk/query 분포

14개 구성 전체 파라미터 sweep은 [10k 합성 데이터의 372개 실측](../07-results/sweep-results-20260911.md)을 완료했습니다. 위의 대규모·실제 데이터 결과와는 구분합니다.

행이나 같은 벡터를 복제해 100k라고 부르지 않습니다. 실제 입력이 들어오면 전용 스크립트가 최소 건수와 synthetic 표식을 검사합니다.

## 클라이언트 비용

타이머는 `VectorStore.search()`를 감싸므로 요청 직렬화와 응답 역직렬화가 포함됩니다. JDBC binary와 HTTP JSON/GraphQL의 비용은 다릅니다. 결과는 ANN 알고리즘만의 시간이 아니라 Spring Boot 검색 경로 시간입니다.

## 작은 10k 데이터

10,000 × 1024 float의 raw vector만 약 39MiB라 대부분 메모리에 들어갑니다. disk index와 대규모 그래프 차이를 일반화할 수 없습니다. 10k는 harness 검증과 shortlist 용도입니다.

## Recall 품질점

그리드가 성기면 0.90·0.95 참고 수준을 건너뛸 수 있습니다. 이는 실패가 아닙니다. 모든 실제 점을 보존하고 관측되지 않은 Recall 수준의 성능을 실측처럼 제시하지 않습니다.

## 필터

기본 합성 query는 필터가 10%뿐이고 기존 tenant×status의 실제 선택도도 1/10/50%가 아닙니다. 전용 generator가 document ID hash 기반 cohort를 만들어 세 실험을 분리합니다. 합산 p95가 아니라 `filtered_*`와 `unfiltered_*`를 각각 봅니다.

## 자원 샘플

Docker stats 명령 완료 후 약 500ms 간격으로 다시 수집합니다. 명령 자체의 시간도 있으므로 정확한 500ms 주기는 아닙니다. 검색 구간 안에서 수집을 시작하고 마친 표본만 집계하며 측정 후 유휴 표본은 사용하지 않습니다. 이번 372개 측정은 각 점을 최소 5초 실행했고 자원 표본은 최소 2개였습니다. 이는 연속 관측이 아니므로 순간 peak를 모두 포착하거나 정밀한 자원 분포를 보장하지 않습니다. OpenSearch는 4GiB heap을 선점하므로 RSS를 실제 live heap처럼 해석하지 않습니다. index size `-1`은 0이 아니라 제품 API 미지원입니다.

## warm cache

파라미터 적용과 200요청 warm-up 후 측정합니다. 구성 안 검색 폭은 오름차순이며, warm-up만으로 캐시·JIT의 안정 상태를 보장하지 않습니다. cold start, 장애 복구, 재색인 중 읽기, write/read 혼합, backup/restore, rolling upgrade는 별도 시험이 필요합니다.

## 통계

새 프로토콜은 3~5회 전체 재구축과 순서 교차를 강제하지만 3회도 넓은 신뢰구간을 추정하기에는 적습니다. median과 range는 안정성 신호이지 보편적 성능 보장이 아닙니다.

## 실제 제품 결정

품질·성능·자원 trade-off를 확인한 뒤 운영 복잡도, 정합성, 장애 복구, 관측성, scale-out 요구를 비교합니다. 과거 단일 실행 결과는 [docs/07-results](../07-results/)에 보존하지만 현재 순위표로 사용하지 않습니다.
