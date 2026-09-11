# Benchmark Overview

동일한 벡터·query·자원 조건에서 다섯 Vector DB의 검색 파라미터를 바꾸며 Recall·latency·자원 변화를 측정하고 산포도로 비교합니다. 현재 실행 기준은 [current-protocol.md](../03-benchmark-design/current-protocol.md)입니다. [2026-09-11 전체 sweep](../07-results/sweep-results-20260911.md)은 14개 구성·124개 검색 파라미터를 3회 측정한 372개 점과 본 측정 검색 2,948,000회를 완료했습니다.

## 측정 항목

| 항목 | 정의 |
|---|---|
| comparison Recall@10 | evaluation 무필터 query의 exact Top-10 대비 ANN Top-10 |
| actual Recall@10 | evaluation 필터·무필터 전체 Recall; 산포도 Y축 |
| latency | `VectorStore.search()` 호출 p50/p95/p99 |
| QPS | evaluation 검색 완료 건수 / 검색 future 완료 시간. Recall 후처리 제외 |
| CPU / RAM | 대상 컨테이너 합계 Avg/Max |
| index ready | drop/create부터 load·flush/refresh·index ready까지 |
| index size | 제품 API로 순수 인덱스를 분리할 수 있는 경우만, 아니면 -1 |

임베딩 생성, LLM 생성, reranking, hybrid search, 다중 노드 성능은 주 측정에 포함하지 않습니다.

## Query 흐름

```text
고정 document/query embedding
  ├─ exact cosine Top-10 ground truth
  └─ query-type 층화 SHA-256 분할
       ├─ calibration 100 → 추가 진단
       └─ evaluation 200 → warm-up + Recall/latency/QPS
```

evaluation은 200 query × measurement 5 = 1,000 search 단위이며, 검색 구간이 최소 5초가 될 때까지 완전한 단위를 반복합니다. calibration과 evaluation ID hash를 결과 환경 정보에 저장합니다.

## 실행 흐름

`run-all-benchmarks.ps1`은 [14개 구성의 파라미터 sweep 행렬](../../data/benchmark-matrix.json)를 인덱스 조합별로 묶고, 매 회차 전체를 재구축합니다. 3~5회를 강제하며 DB 순서를 교차합니다. 동일 파라미터·동일 부하의 반복만 묶어 평균·median·p95/p99·분산을 기록합니다. 산포도에는 모든 실제 점과 0.90·0.95 수평 참고선을 표시합니다.

1%/10%/50% 필터 선택도, 100k/1M, 실제 프로젝트 non-synthetic 입력은 별도 후속 실험입니다. 실행 코드는 있으나 이번 372개 결과에 포함된 검증은 아닙니다.
