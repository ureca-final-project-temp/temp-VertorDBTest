# Dataset와 Query Set

## 개요

한국어 합성 기술문서 코퍼스입니다. 재현 가능한 DB 비교가 목적이며 실제 서비스 데이터가 아닙니다.

| 파일 | 내용 |
|---|---|
| `data/documents_10000.jsonl` | 10,000 chunk |
| `data/queries_300.jsonl` | 300 query |
| `data/qrels.tsv` | query별 의미상 관련 chunk label |
| `data/query_distribution.csv` | query 유형별 개수/비율 |
| `data/manifest.json` | 실험 고정 조건 |
| `data/embeddings/` | 생성된 벡터와 manifest (gitignore) |
| `data/sample/` | 4차원 20건 smoke test용 |

## Corpus

- 10,000건, 전부 한국어
- 200개 topic
- 필드: `id`, `document_id`, `chunk_id`, `content`, `category`, `subcategory`, `language`,
  `tenant_id`, `status`, `access_level`, `chunk_profile`, `tags`, `topic_key`, `title`

`id` / `documentId` / `chunkId` / `content` / `embedding`을 제외한 나머지 필드가
전부 `metadata` 객체로 들어갑니다. 필터는 이 하위 키를 대상으로 합니다.

## Query Set

| 유형 | 개수 | 비율 |
|---|---:|---:|
| short_clear | 60 | 20% |
| long_natural | 60 | 20% |
| technical_term | 45 | 15% |
| semantic_paraphrase | 45 | 15% |
| exact_identifier | 30 | 10% |
| ambiguous | 30 | 10% |
| **metadata_filter** | **30** | **10%** |

영어 또는 혼용 질의 21건을 포함해 BGE-M3의 multilingual 특성을 일부 반영합니다.

## 필터 분포 — 결과 해석에 직접 영향

**300개 중 `metadata_filter` 30개(정확히 10%)만 필터를 가집니다.**

```text
{"tenant_id":"beta","status":"active"}    12건
{"tenant_id":"alpha","status":"active"}   10건
{"tenant_id":"delta","status":"active"}    7건
{"tenant_id":"gamma","status":"active"}    1건
나머지 270건                                필터 없음
```

이 비율은 지표 해석에 결정적입니다.

필터 질의가 더 느린 제품에서는 이 30개가 통째로 지연 분포의 상위 10%를 차지합니다.
즉 **합산 p95와 p99는 ANN 꼬리지연이 아니라 필터 비용**이 됩니다.

그래서 이 저장소는 두 집단을 분리해 기록합니다.

```text
filtered_p50_ms   filtered_p95_ms   filtered_recall
unfiltered_p50_ms unfiltered_p95_ms unfiltered_recall
```

자세한 읽는 법은 [metrics.md](metrics.md)의 "필터 질의를 섞은 percentile은 읽지 않는다" 절에 있습니다.

## 데이터 규모의 한계

10,000건은 HNSW의 특성을 관찰하기에 **작은 편**입니다.

- 벡터 전체가 39 MiB로 어떤 DB에서도 메모리에 다 올라갑니다. 디스크 접근 패턴 차이가 드러나지 않습니다.
- 그래프가 얕아 `ef`를 조금만 올려도 Recall이 빠르게 포화합니다.
  실제로 OpenSearch는 최소 후보에서 이미 0.93에 도달해 낮은 목표를 맞출 수 없습니다.
- Qdrant는 기본 설정이면 이 크기에서 exact scan을 씁니다. 임계값을 낮춰 강제로 인덱싱합니다.

최종 선정 전에는 실제 서비스 규모의 데이터로 다시 측정해야 합니다.

## 두 가지 평가의 분리

### 1. ANN 성능 비교 (이 저장소의 주 목적)

exact Top-K를 Ground Truth로 두고 ANN Top-K와 비교합니다.
embedding 품질과 무관하게 "DB가 exact를 얼마나 재현하는가"만 봅니다.

### 2. 의미 검색 품질 확인 (선택)

`data/qrels.tsv`로 실제 target topic 문서가 검색됐는지 평가합니다.
이 지표는 Vector DB뿐 아니라 embedding·chunking 품질의 영향을 함께 받으므로
DB 비교에 섞으면 안 됩니다.

## 벡터 생성

```powershell
.\gradlew.bat generateEmbeddings
```

[../02-concepts/chunk-and-embedding.md](../02-concepts/chunk-and-embedding.md)를 봅니다.

## 관련 문서

- [ground-truth.md](ground-truth.md)
- [metrics.md](metrics.md)
- [limitations.md](limitations.md)
