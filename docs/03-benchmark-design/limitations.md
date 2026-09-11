# Limitations

## 아직 결과가 없는 축

현재 저장소에는 합성 10k embedding만 있습니다. 다음은 실행 코드와 fail-fast guard는 있으나 유효 입력이 없어 최종 수치가 없습니다.

- 실제로 임베딩한 100k/1M 데이터
- 실제 프로젝트 FAQ/chunk/query 분포
- 전체 T01~T28의 새 프로토콜 공식 결과

행이나 같은 벡터를 복제해 100k라고 부르지 않습니다. 실제 입력이 들어오면 전용 스크립트가 최소 건수와 synthetic 표식을 검사합니다.

## 클라이언트 비용

타이머는 `VectorStore.search()`를 감싸므로 요청 직렬화와 응답 역직렬화가 포함됩니다. JDBC binary와 HTTP JSON/GraphQL의 비용은 다릅니다. 결과는 ANN 알고리즘만의 시간이 아니라 Spring Boot 검색 경로 시간입니다.

## 작은 10k 데이터

10,000 × 1024 float의 raw vector만 약 39MiB라 대부분 메모리에 들어갑니다. disk index와 대규모 그래프 차이를 일반화할 수 없습니다. 10k는 harness 검증과 shortlist 용도입니다.

## Recall 품질점

후보 그리드에 목표 ±0.01이 없으면 `calibration_selection=CLOSEST_AVAILABLE`을 기록합니다. independent evaluation의 `target_met`은 달라질 수 있습니다. 서로 다른 실제 Recall의 latency를 같은 품질점처럼 직접 비교하지 않습니다.

## 필터

기본 합성 query는 필터가 10%뿐이고 기존 tenant×status의 실제 선택도도 1/10/50%가 아닙니다. 전용 generator가 document ID hash 기반 cohort를 만들어 세 실험을 분리합니다. 합산 p95가 아니라 `filtered_*`와 `unfiltered_*`를 각각 봅니다.

## 자원 샘플

Docker stats sampling은 약 500ms 주기입니다. 매우 짧은 측정은 CPU sample이 적을 수 있습니다. OpenSearch는 4GiB heap을 선점하므로 RSS를 실제 live heap처럼 해석하지 않습니다. index size `-1`은 0이 아니라 제품 API 미지원입니다.

## warm cache

튜닝과 warm-up 후 측정하므로 결과는 warm-cache 조건입니다. cold start, 장애 복구, 재색인 중 읽기, write/read 혼합, backup/restore, rolling upgrade는 별도 시험이 필요합니다.

## 통계

새 프로토콜은 3~5회 전체 재구축과 순서 교차를 강제하지만 3회도 넓은 신뢰구간을 추정하기에는 적습니다. median과 range는 안정성 신호이지 보편적 성능 보장이 아닙니다.

## 실제 제품 결정

기본 의사결정 규칙을 통과한 후보끼리 운영 복잡도, 정합성, 장애 복구, 관측성, scale-out 요구를 비교합니다. 과거 단일 실행 결과는 [docs/07-results](../07-results/)에 보존하지만 현재 순위표로 사용하지 않습니다.
