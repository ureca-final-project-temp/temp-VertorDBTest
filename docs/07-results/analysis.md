# Analysis

1차 본실험(2026-09-11) 결과를 검토하고 발견한 측정 결함, 그 근거와 수정 내용입니다.

## 요약

| 결함 | 영향 | 상태 |
|---|---|---|
| Qdrant payload index 누락 | 필터 질의가 full scan. 판정 1번이 뒤집힘 | 수정 완료 |
| 필터/무필터 질의 혼재 | 합산 p95·p99가 ANN 꼬리가 아님 | 수정 완료 |
| 낮은 목표 Recall 도달 불가 | 15칸 중 6칸만 비교 가능 | **미해결** |
| CPU·메모리 비교 가능성 | 절반 이상의 행이 사용 불가 | 문서화만 |

1차 실행의 원본 수치는 [benchmark-results.md](benchmark-results.md)에 기록으로 남겨두었습니다.

---

## 결함 1 — Qdrant payload index 누락

### 증상

Qdrant의 p95가 다른 DB보다 4~5배 높았습니다.

```text
Qdrant    0.80  p50 2.48 ms   p95 130.74 ms
Weaviate  0.80  p50 12.53 ms  p95 29.73 ms
```

이 수치로 "Weaviate가 Qdrant보다 빠르다"고 판정했습니다.

### 근거

`hnsw_ef`를 12.5배 올려도 p95가 움직이지 않았습니다.

| hnsw_ef | p50 | p95 |
|---:|---:|---:|
| 80 | 2.48 ms | 130.74 ms |
| 400 | 3.78 ms | 104.25 ms |
| 1000 | 5.04 ms | 126.64 ms |

p50은 정상적으로 반응하는데 p95만 무반응입니다.
**탐색 파라미터에 반응하지 않는 경로는 HNSW를 타고 있지 않습니다.**

원인은 컬렉션 생성 코드에 payload index 생성이 없었던 것입니다.
Qdrant는 필터 대상 필드에 payload index가 없으면 filtered HNSW를 쓰지 못하고
전체 점수를 스캔합니다.

### 결론

판정 1번은 성립하지 않습니다. 무필터 질의만 보면 순서가 반대입니다.

```text
무필터 p50:  Qdrant 2.48 ms  <  Weaviate 12.53 ms
```

### 수정

```yaml
vector.qdrant.payload-index-fields:
  tenant_id: keyword
  status: keyword
  ...
```

컬렉션 생성 후 `metadata.<키>`에 payload index를 만들고,
`awaitReady()`가 `payload_schema`에 선언 필드가 모두 있는지 확인합니다.
없으면 측정을 시작하지 않고 실패합니다.

실제 Qdrant 1.19.0 컨테이너로 API 모양과 `payload_schema` 반영을 검증했습니다.

---

## 결함 2 — p95·p99가 ANN 꼬리지연이 아님

### 증상

질의 300개 중 `metadata_filter` 30개, 정확히 10%만 필터를 가집니다.
필터 질의가 더 느리므로 **이 30개가 통째로 지연 분포의 상위 10%를 차지합니다.**

즉 p95도 p99도 필터 질의 집단의 값입니다.

### 근거

필터 비율이 `r`일 때 `average ≈ (1-r)·p50 + r·p95`가 성립하면
percentile이 필터 집단에 지배당하고 있다는 뜻입니다. 15행 전부에서 성립했습니다.

| 행 | p50 | p95 | 예측 avg | 실측 avg |
|---|---:|---:|---:|---:|
| Qdrant 0.80 | 2.48 | 130.74 | 15.7 | **15.65** |
| Weaviate 0.80 | 12.53 | 29.73 | 14.3 | **14.31** |
| Milvus 0.95 | 5.79 | 26.88 | 7.9 | **7.85** |
| pgvector 0.95 | 5.04 | 34.07 | 8.0 | **7.46** |

두 번째 근거는 `ef` 무반응입니다. Weaviate도 `ef` 40→400에서
p50은 12.53→13.97로 오르는데 p95는 29.73→27.88로 움직이지 않습니다.

### 수정

`LatencyCollector`가 필터/무필터 계열을 따로 모으고,
결과에 `filtered_*`와 `unfiltered_*` 컬럼 12개를 추가했습니다.
SVG 차트의 x축도 합산 p95에서 unfiltered p95로 바꿨습니다.

`filtered_recall`과 `unfiltered_recall`을 함께 기록하므로
post-filtering으로 Top-K를 못 채우는 제품도 드러납니다.

---

## 결함 3 — 낮은 목표 Recall 도달 불가 (미해결)

15칸 중 `WITHIN_TOLERANCE`는 6칸뿐이었습니다.

최소 후보에서 이미 목표를 넘어버리는 DB가 있습니다.

```text
OpenSearch ef_search=10 → Recall 0.9303    ← 0.80, 0.90 도달 불가
Milvus     ef=40        → Recall 0.9030    ← 0.80 도달 불가
```

후보는 `candidate >= topK`로 걸러지므로 10 밑으로 내려갈 수 없습니다.
`ef`로 Recall을 낮출 수 없으면 `M` / `ef_construction`을 낮춰야 하는데
현재 두 값은 전 DB에서 16 / 128로 고정되어 튜닝 축에 없습니다.

데이터가 10,000건으로 작아 HNSW 그래프가 얕은 것이 근본 원인입니다.

**결과적으로 "동일 Recall 비교"가 성립한 행은 0.95 세 행과 Qdrant/Weaviate 0.80 두 행뿐입니다.**

---

## 결함 4 — CPU·메모리 비교 가능성

- CPU가 0.01~0.7%로 기록된 5개 행은 `docker stats`의 약 1초 집계창보다
  측정 구간이 짧아 생긴 과소 집계입니다. 이 행들을 빼면 CPU 비교에 15행 중 11행만 남습니다.
- OpenSearch의 4,835 MiB는 `-Xms4g -Xmx4g` 선점 heap입니다. 사용량이 아니라 설정값입니다.

이 두 컬럼은 현재 구조에서 DB 간 직접 비교에 쓸 수 없습니다.
[../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)에 명시했습니다.

---

## 재실행 시 확인할 것

1. Qdrant 무필터 p50/p95가 이전 2.48 / 130.74에서 어떻게 바뀌는가
2. `filtered_p95_ms`가 DB별로 어떻게 갈리는가 — 필터 처리 능력의 실제 비교
3. `filtered_recall` < `unfiltered_recall`인 DB가 있는가 — post-filtering 신호
4. `recall_selection`이 `WITHIN_TOLERANCE`인 행이 몇 개인가

```powershell
.\gradlew.bat bootJar
.\scripts\run-all-benchmarks.ps1 -ResultDirectory benchmark-result/rerun-01
```

## 배운 것

**조용한 성능 저하가 가장 위험합니다.**
Qdrant는 오류를 내지 않았습니다. 그냥 느렸고, 그 숫자가 그대로 표에 들어갔습니다.

그래서 설정 누락으로 제품이 느린 경로에 빠지면 측정 대신 실패하도록 바꿨습니다.
[../06-implementation/adapter-policy.md](../06-implementation/adapter-policy.md)의 8번 규칙입니다.

**percentile은 단일 워크로드에서만 의미가 있습니다.**
성격이 다른 질의를 한 분포에 섞으면 percentile은 두 집단의 경계를 가리킵니다.
워크로드를 나눠 측정하는 것이 유일한 해법입니다.

## 관련 문서

- [benchmark-results.md](benchmark-results.md)
- [decision.md](decision.md)
- [../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)
