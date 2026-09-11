# Limitations

이 실험이 **답하지 않는 질문**과 **결과를 왜곡할 수 있는 조건**입니다.
결과를 인용하기 전에 이 문서를 먼저 봅니다.

## 1. 측정 구간에 클라이언트 비용이 포함됩니다

타이머는 `VectorStore.search()`를 감싸므로 요청 직렬화와 응답 역직렬화가 안에 들어갑니다.
그 비용이 DB마다 다릅니다.

| DB | 1024차원 질의 벡터 전송 방식 | 상대 비용 |
|---|---|---|
| pgvector | `PGvector` 바이너리 (JDBC) | 낮음 |
| Qdrant / Milvus / OpenSearch | JSON float 배열 (`List<Float>` 박싱) | 중간 |
| Weaviate | 1024개 float를 join한 GraphQL 쿼리 문자열 (~12 KB) | 높음 |

따라서 이 지표는 "ANN 알고리즘 성능"이 아니라
**Spring Boot 애플리케이션에서 그 DB를 쓸 때의 전체 검색 경로 성능**입니다.

pgvector는 추가로 매 질의마다 `set_config` 왕복을 한 번 더 소모합니다(세션 파라미터 적용).

## 2. 필터 질의가 10%뿐입니다

질의 300개 중 30개만 필터를 가집니다. 필터 질의가 느린 제품에서는
그 30개가 통째로 지연 분포의 상위 10%를 차지합니다.

**합산 p95·p99를 ANN 꼬리지연으로 읽으면 안 됩니다.**
`unfiltered_*`와 `filtered_*`를 나눠 봅니다. [metrics.md](metrics.md)를 봅니다.

또한 필터 선택도가 한 종류(tenant × status)뿐입니다.
선택도가 1%일 때와 50%일 때 제품별 거동이 크게 달라지지만 이 실험은 그 축을 다루지 않습니다.

## 3. 데이터가 작습니다

10,000건 × 1024차원 = 약 39 MiB. 전 DB에서 메모리에 다 올라갑니다.

- 디스크 접근 패턴 차이가 드러나지 않습니다.
- HNSW 그래프가 얕아 `ef`를 조금만 올려도 Recall이 포화합니다.
- 실서비스 규모(수백만 건)의 거동을 예측하는 근거로 쓸 수 없습니다.

## 4. 낮은 목표 Recall을 맞출 수 없는 DB가 있습니다

후보는 `[10, 20, 40, 80, 120, 200, 400, 800, 1000]`이고 `candidate >= topK` 조건으로 걸러집니다.
즉 10 밑으로 내려갈 수 없습니다.

```text
OpenSearch ef_search=10 → 비교 Recall 0.9296    ← 0.80, 0.90 도달 불가
Milvus     ef=40        → 비교 Recall 0.8959    ← 0.80 도달 불가
```

이 경우 `ef`가 아니라 `M` / `ef_construction`을 낮춰야 하지만
현재 두 값은 전 DB에서 16 / 128로 고정되어 튜닝 축에 없습니다.

**결과적으로 "동일 Recall 비교"가 모든 목표에서 성립하지는 않습니다.**
`recall_selection=CLOSEST_AVAILABLE`인 행은 서로 비교할 수 없습니다. 또한 Milvus처럼
튜닝과 본 측정 사이 Recall이 흔들릴 수 있으므로 `WITHIN_TOLERANCE`라도
`target_met=false`이면 직접 비교에서 제외합니다.

## 5. CPU와 메모리 수치의 비교 가능성이 낮습니다

- **OpenSearch**: `-Xms4g -Xmx4g`로 heap을 선점합니다. `peak_memory_bytes`는 사용량이 아니라 설정값입니다.
- **짧은 측정 구간**: `docker stats`는 약 1초 집계창을 가집니다. 전체 측정이 그보다 짧으면
  CPU 평균이 심하게 과소 집계됩니다. 0.01% 같은 값이 나오면 그 행은 CPU 비교에서 제외합니다.
- **샘플링 방식**: 500ms마다 `docker stats --no-stream` 프로세스를 fork합니다. 짧은 측정에서는 샘플이 1~2개입니다.
- `index_size_bytes`는 DB가 직접 제공하는 범위에서만 기록하며 미지원은 `-1`입니다.
  제품마다 "인덱스 크기"의 정의가 달라 직접 비교하기 어렵습니다.

## 6. 측정 환경

- **Windows + Docker Desktop(WSL2)**: HTTP 기반 DB는 전부 포트포워드 프록시를 통과합니다.
- **클라이언트와 서버가 같은 호스트**: JVM 클라이언트에 자원 상한이 없습니다.
  동시성 10으로 1024차원 JSON을 직렬화하면 클라이언트 CPU를 상당히 씁니다.
- **PostgreSQL 동시 기동**: 모든 프로필에서 Source of Truth용 PostgreSQL이 4 vCPU / 8 GiB를 함께 점유합니다.

## 7. 반복 횟수가 부족합니다

- 인덱스 빌드 1회. 빌드 편차를 반영하지 않습니다.
- 실행 순서 교차 없음. 앞선 실행의 캐시 상태 영향을 분리하지 않았습니다.
- 자동 튜닝이 후보 전체에 warm-up과 5회 반복을 수행한 뒤 본 측정을 하므로
  **결과는 warm-cache 조건**입니다. cold-cache 결과와 섞지 않습니다.

최종 선정 전에는 최소 3회 재구축 반복과 실행 순서 교차가 필요합니다.

## 8. 다루지 않는 축

| 축 | 상태 |
|---|---|
| Hybrid search (lexical + vector) | 미측정 |
| Reranking | 미측정 |
| 다중 노드 / shard / replica | 미측정 |
| 쓰기와 읽기 동시 부하 | 미측정 |
| 장애 복구, 재색인 중 서비스 영향 | 미측정 |
| 백업/복구, rolling upgrade | 미측정 |
| Qdrant tenant 최적화(`is_tenant`), shard key | 미측정 |

## 9. 데이터가 합성입니다

한국어 합성 기술문서이며 실제 서비스 문서 분포가 아닙니다.
실제 선정 직전에는 실서비스 문서·질의 분포로 같은 벤치마크를 한 번 더 수행해야 합니다.

## 결과를 인용하는 올바른 방법

이렇게 쓰면 안 됩니다.

```text
Qdrant가 pgvector보다 빠르다.
```

이렇게 씁니다.

```text
본 테스트 환경(10,000건 / 1024차원 / 4 vCPU / 8 GiB / 동시성 10 / warm cache)에서
무필터 Recall@10 0.95 ±0.01을 본 측정에서도 만족한 단일 재구축 실행 기준으로,
무필터 질의의 p95가 Qdrant 5.62 ms, pgvector 31.05 ms였다.
```

이 수치는 2026-09-11 단일 실행이며 반복 재구축의 중앙값이 아니다.

## 관련 문서

- [controlled-variables.md](controlled-variables.md)
- [metrics.md](metrics.md)
- [../07-results/analysis.md](../07-results/analysis.md)
