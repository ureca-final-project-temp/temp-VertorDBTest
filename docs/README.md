# 문서 안내

현재 결과는 **2026-09-11 전체 검색 파라미터 sweep의 372개 실측**입니다. 14개 DB·엔진·인덱스 구성을 각각 3회 재구축하고, 회차별 124개 검색 파라미터를 모두 측정했습니다. Recall 0.90·0.95는 산포도의 수평 참고선입니다.

## 결과부터 읽기

| 문서 | 확인할 내용 |
|---|---|
| [전체 sweep 실측 보고서](07-results/sweep-results-20260911.md) | 전체 산포도, 구성별 범위, 같은 파라미터의 반복 변동과 해석 |
| [372개 산포도 SVG](07-results/assets/sweep-20260911-220549/scatter-recall-latency-all-372.svg) | X=p95 latency, Y=Recall@10; 모든 실제 점과 자원 정보 |
| [측정 근거 파일 안내](07-results/assets/sweep-20260911-220549/README.md) | 원시·집계 파일, 검증 결과, 입력·소스·실행 식별자 |
| [비교 결과 해석 기준](07-results/decision.md) | 품질 참고선과 실제 측정값을 비교하는 방법 |
| [과거 결과 목록](07-results/README.md) | 이전 목표별 선택 방식과 결함 분석의 보존 기록 |

## 설계와 실행 이해하기

1. [벤치마크 개요](01-overview/benchmark-overview.md)와 [아키텍처](01-overview/architecture.md)에서 측정 대상을 확인합니다.
2. [Recall](02-concepts/recall.md), [Exact와 ANN](02-concepts/exact-vs-ann.md), [HNSW](02-concepts/hnsw.md)에서 탐색 폭과 품질의 관계를 읽습니다.
3. [현재 프로토콜](03-benchmark-design/current-protocol.md), [고정 조건](03-benchmark-design/controlled-variables.md), [실험 설계](03-benchmark-design/experiment-design.md)를 확인합니다.
4. [데이터와 질의](03-benchmark-design/dataset-and-queryset.md), [Ground Truth](03-benchmark-design/ground-truth.md), [측정 지표](03-benchmark-design/metrics.md), [한계](03-benchmark-design/limitations.md)를 함께 읽습니다.
5. [로컬 준비](04-quickstart/local-setup.md)와 [실행 방법](04-quickstart/run-benchmark.md)을 따라 새 결과 디렉터리에서 실행합니다.

## 구현과 문제 해결

- DB별 구성과 이번 실측: [pgvector](05-databases/pgvector.md), [Qdrant](05-databases/qdrant.md), [Weaviate](05-databases/weaviate.md), [Milvus](05-databases/milvus.md), [OpenSearch](05-databases/opensearch.md)
- 구현 계약: [VectorStore와 IndexManager](06-implementation/vector-store.md), [어댑터 정책](06-implementation/adapter-policy.md), [코드 지도](06-implementation/code-architecture.md)
- 측정값 확인: [결과 형식](06-implementation/result-format.md), [문제 해결](08-troubleshooting/common-issues.md)

이번 측정은 10k 합성 데이터, Top-10, 동시성 10, DB 대상 합계 4 vCPU/8 GiB 조건입니다. 100k/1M·실제 FAQ·별도 필터 선택도 실험은 이번 결과에 포함되지 않습니다. 과거 84개 결과의 목표 판정 필드를 새 sweep에 적용하지 않습니다.

[프로젝트 README](../README.md)
