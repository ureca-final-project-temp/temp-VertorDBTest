# Exact Search와 ANN

## Exact Search

모든 문서와 거리를 계산해 진짜 Top-K를 찾습니다. 항상 정확하지만 문서 수에 비례해 느려집니다.

10,000건 × 1024차원이면 질의 하나에 약 1,000만 번의 곱셈이 필요합니다.
100만 건이면 100배가 됩니다.

## ANN (Approximate Nearest Neighbor)

인덱스를 만들어 후보를 좁힌 뒤 그 안에서만 비교합니다. 훨씬 빠르지만 **정답을 놓칠 수 있습니다.**

```text
Exact:  10,000건 전부 비교          → 느림, Recall = 1.0
ANN:    수백 건만 비교              → 빠름, Recall < 1.0
```

## 핵심 트레이드오프

ANN은 탐색 폭을 줄이면 **항상** 빨라집니다. 그래서 속도만 비교하면 의미가 없습니다.

```text
탐색 폭 ↓  →  latency ↓, Recall ↓
탐색 폭 ↑  →  latency ↑, Recall ↑
```

공정한 비교는 이렇게 해야 합니다.

> Recall을 같은 수준으로 맞춘 뒤, 그때의 latency를 비교한다.

이 프로젝트가 목표 Recall(0.80 / 0.90 / 0.95)을 먼저 정하고
DB별 탐색 파라미터를 자동으로 찾는 이유입니다.

## 이 프로젝트에서 Exact Search의 역할

Exact Search는 비교 대상이 아니라 **채점 기준**입니다.

```text
Java ExactSearchEngine  →  Exact Top-10  →  Ground Truth
                                              ↓
                          DB의 ANN Top-10과 비교  →  Recall@10
```

DB가 제공하는 exact 모드를 쓰지 않고 Java brute-force로 직접 계산합니다.
DB의 exact 구현에 버그가 있어도 채점 기준은 오염되지 않습니다.

자세한 내용은 [../03-benchmark-design/ground-truth.md](../03-benchmark-design/ground-truth.md)를 봅니다.

## 언제 ANN이 필요 없는가

문서 수가 적으면 exact가 충분히 빠릅니다. 실제로 Qdrant는 컬렉션이 임계값보다 작으면
의도적으로 exact scan을 씁니다. 그러면 HNSW를 측정하려던 실험이 exact를 측정하게 됩니다.

그래서 이 프로젝트는 임계값을 낮춰 강제로 인덱스를 만들게 합니다.

```yaml
vector.qdrant.full-scan-threshold: 10   # KB
vector.qdrant.indexing-threshold: 10    # KB
```

같은 이유로 pgvector는 검색 세션에 `enable_seqscan=off`를 설정합니다.

## 관련 문서

- [hnsw.md](hnsw.md)
- [recall.md](recall.md)
