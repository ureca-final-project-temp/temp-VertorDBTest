# BGE-M3 Vector DB Benchmark Dataset

이 데이터셋은 pgvector / Qdrant / Weaviate / Milvus / OpenSearch를 동일 조건에서 비교하기 위한 합성 기술문서 코퍼스다.

## 파일

- `documents_10000.jsonl`: 10,000개 chunk record
- `queries_300.jsonl`: 300개 고정 query
- `qrels.tsv`: query별 의미상 관련 chunk label
- `query_distribution.csv`: query 유형별 개수/비율
- `manifest.json`: benchmark 고정 조건

## BGE-M3 적용

원본 데이터셋에는 embedding vector 자체가 포함되어 있지 않다.
현재 프로젝트에서는 로컬 Ollama의 `bge-m3:latest`로 `documents_10000.jsonl.content`와
`queries_300.jsonl.text`를 한 번만 embedding하여 다음 파일을 생성했다.

- `embeddings/document-vectors.jsonl`
- `embeddings/query-vectors.jsonl`
- `embeddings/embedding-manifest.json`
- `queries/queries.jsonl`

동일한 vector 파일을 모든 Vector DB에 재사용한다. 재생성 명령은 `./gradlew generateEmbeddings`이며,
manifest의 model digest와 SHA-256이 실험 데이터 동일성의 기준이다.

DB benchmark에서는 embedding 생성 시간을 latency에 포함하지 않는다.

## Chunking 조건

입력 코퍼스는 벤치마크 전에 이미 10,000개의 합성 chunk record로 만들어져 있다.
따라서 이 저장소 안에서 실행되는 단일 chunking 알고리즘·고정 크기·overlap 값은 없으며
`manifest.json`에 각각 해당 없음(`null`)으로 기록한다. 실제 입력의 `chunk_profile`
(`short` / `medium` / `long`)과 최종 chunk 수 10,000건이 재현 조건이다.

## Query 유형

- short_clear
- long_natural
- technical_term
- semantic_paraphrase
- exact_identifier
- ambiguous
- metadata_filter

일부 영어 query를 포함해 BGE-M3의 multilingual 특성을 일부 반영했다.

## 평가를 두 가지로 분리

### 1. ANN 성능 비교
각 query vector에 대해 Exact Top-K를 ground truth로 생성하고 ANN Top-K와 비교한다.

`ANN Recall@10 = |Exact Top-10 ∩ ANN Top-10| / 10`

DB 간 주 비교 Target Recall@10은 0.80 / 0.90 / 0.95이며 허용 범위는 각 목표의 ±0.01이다.
해당 범위에 드는 검색 설정이 없으면 후보 중 목표에 가장 가까운 실제 Recall을 사용하고
`CLOSEST_AVAILABLE`로 표시한다. 0.70 / 0.99는 필요할 때만 보조 실험으로 측정한다.

### 2. 의미 검색 품질 확인 (선택)
`qrels.tsv`를 사용해 실제 target topic 문서가 검색됐는지 별도로 평가할 수 있다.
이 지표는 Vector DB 자체뿐 아니라 embedding/chunking 품질의 영향을 함께 받는다.

## 주의

이 데이터는 재현 가능한 DB 비교용 합성 데이터다.
실제 서비스 선정 직전에는 실제 서비스 문서/질의 분포로 동일 benchmark를 한 번 더 수행하는 것이 적절하다.
