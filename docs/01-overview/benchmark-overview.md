# Benchmark Overview

동일한 벡터·query·자원 조건에서 Recall@10 품질점을 맞춘 뒤 다섯 Vector DB의 검색 경로를 비교합니다. 현재 실행 기준은 [current-protocol.md](../03-benchmark-design/current-protocol.md)입니다.

## 측정 항목

| 항목 | 정의 |
|---|---|
| comparison Recall@10 | evaluation 무필터 query의 exact Top-10 대비 ANN Top-10 |
| actual Recall@10 | evaluation 필터·무필터 전체 참고값 |
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
       ├─ calibration 100 → search parameter 선택
       └─ evaluation 200 → warm-up + Recall/latency/QPS
```

최종 evaluation은 200 query × measurement 5 = case당 1,000 search입니다. calibration과 evaluation ID hash를 결과 환경 정보에 저장합니다.

## 실행 흐름

`run-all-benchmarks.ps1`은 [28-case matrix](../../data/benchmark-matrix.json)를 인덱스 조합별로 묶고, 매 회차 전체를 재구축합니다. 3~5회를 강제하며 DB 순서를 교차합니다. 최종 표는 단일 행이 아니라 Recall 평균/범위와 median p95/QPS를 사용합니다.

별도 현실성 검증은 1%/10%/50% 필터 선택도, 100k/1M shortlist, 실제 프로젝트 non-synthetic 입력으로 수행합니다.
