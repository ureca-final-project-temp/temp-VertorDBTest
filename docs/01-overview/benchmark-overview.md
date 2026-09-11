# Benchmark Overview

이 문서를 읽으면 이 실험이 **무엇을 측정하고 무엇을 측정하지 않는지** 판단할 수 있습니다.

## 한 문장 요약

동일한 벡터·질의·자원 조건에서 Recall@10을 맞춘 뒤, 다섯 Vector DB의 검색 지연시간과 자원 사용량을 비교합니다.

## 측정하는 것

| 항목 | 정의 |
|---|---|
| 비교 Recall@10 | 무필터 질의에서 Java brute-force exact Top-10 대비 ANN Top-10의 일치 비율 |
| 전체 Recall@10 | 필터·무필터를 합친 실제 질의 집합의 참고 지표 |
| Latency | `VectorStore.search()` 한 번의 실행 시간. p50 / p95 / p99 |
| QPS | 측정 구간 전체 요청 수 ÷ 측정 구간 소요 시간 |
| CPU / RAM | 대상 컨테이너의 `docker stats` 합계 |
| Time-to-ready | drop/create → 적재 → 비동기 인덱싱 완료까지의 검색 준비 시간 |
| Disk write | 검색 측정 중 Docker Block I/O write 증가량 |

## 측정하지 않는 것

- **embedding 생성 시간** — 벡터는 사전 생성해 파일로 고정합니다. 모든 DB가 같은 float를 받습니다.
- **LLM 생성 시간** — 이 저장소는 retrieval 구간만 다룹니다.
- **검색 품질(semantic relevance)** — Recall은 "exact 검색과 얼마나 같은가"이지 "좋은 문서를 찾았는가"가 아닙니다.
  후자는 `data/qrels.tsv`로 별도 평가합니다. [recall.md](../02-concepts/recall.md)를 봅니다.
- **Hybrid search, reranking, 다중 노드 확장** — 단일 노드 dense ANN만 봅니다.

## 전체 흐름

```text
data/documents_10000.jsonl ─┐
data/queries_300.jsonl ─────┴─> Ollama bge-m3 ─> 1024차원 벡터 JSONL (SHA-256 고정)
                                                     │
                    ┌────────────────────────────────┤
                    ↓                                ↓
        Java ExactSearchEngine                VectorStore Adapter
        (brute-force Top-10)                  (pgvector/Qdrant/...)
                    │                                │
                    └──> Ground Truth ──> Recall@10 <┘
                                             │
                            무필터 Recall 목표 구간(0.80/0.90/0.95 ±0.01)에 맞는
                            탐색 파라미터 자동 선택
                                             │
                                             ↓
                              본 측정 (warm-up 1회, 측정 5회, 동시성 10)
                                             │
                                             ↓
                              JSON + CSV + SVG (benchmark-result/)
```

## 실행 단위

한 번의 `POST /api/benchmarks/run` 요청이 **하나의 DB**에 대해 여러 시나리오를 실행합니다.
DB를 바꾸려면 Spring 프로필과 Docker 프로필을 함께 바꿔 다시 실행합니다.
다섯 DB를 순서대로 도는 것은 `scripts/run-all-benchmarks.ps1`이 담당합니다.

한 DB의 본실험 1회는 목표 3개 × 300질의 × 5회 = 4,500건의 측정 요청과,
그에 앞선 자동 튜닝 요청으로 구성됩니다. 자동 튜닝은 ANN 자체 비교 모집단인 무필터
270개 질의만 사용하고, 본 측정은 전체 300개를 실행해 필터/무필터 결과를 따로 기록합니다.

## 다음 문서

- 시스템 구조와 측정 타이머의 정확한 범위 → [architecture.md](architecture.md)
- 실험 설계와 통제 변수 → [../03-benchmark-design/experiment-design.md](../03-benchmark-design/experiment-design.md)
- 이 실험의 한계 → [../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)
