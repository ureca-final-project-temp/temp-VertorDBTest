# Recall@K

이 실험에서 **검색 품질을 맞추는 기준**입니다.

## 정의

```text
Recall@10 = |Exact Top-10 ∩ ANN Top-10| / min(10, Exact 결과 수)
```

exact 검색이 찾은 10개 중 ANN이 몇 개를 찾았는지의 비율입니다.

```text
Exact Top-10 : [A, B, C, D, E, F, G, H, I, J]
ANN Top-10   : [A, B, C, D, E, F, G, H, X, Y]
                                        └──┴── 2개 놓침

Recall@10 = 8 / 10 = 0.8
```

## 분모가 min(10, exact 결과 수)인 이유

필터가 강해서 조건을 만족하는 문서가 10개보다 적으면, 분모를 10으로 두면
아무리 잘 찾아도 1.0이 나올 수 없습니다.

```java
int denominator = Math.min(k, exact.size());
if (denominator == 0) return 1.0;
```

exact 결과가 0개면 "찾을 게 없었다"이므로 1.0으로 처리합니다.

## Recall이 아닌 것

Recall@K는 **exact 검색과 얼마나 같은가**입니다.
"좋은 문서를 찾았는가"가 아닙니다.

embedding이 나쁘면 exact 검색 결과 자체가 엉망이고, ANN이 그걸 그대로 재현하면 Recall은 1.0입니다.
의미 품질은 `data/qrels.tsv`로 따로 평가해야 하며, 그 지표는 Vector DB가 아니라
embedding과 chunking 품질의 영향을 함께 받습니다.

## 목표 구간

| 목표 | 성격 |
|---|---|
| 0.90 | 실무에서 자주 쓰는 균형점 |
| 0.95 | 품질 우선. 대부분 정답을 회수 |

허용오차는 각 목표의 **±0.01**입니다.

주 비교는 evaluation 200개 중 필터가 없는 질의의 Recall을 사용합니다. 결과의
`comparison_recall`이 그 값이며, evaluation 필터를 포함한 `actual_recall`은 혼합 참고값입니다.

보조 목표 0.70과 0.99는 주 비교표에 섞지 않고 필요할 때 별도로 실행합니다
(`data/benchmark-request-auxiliary.json`).

## 선택 방식 표기

결과의 `calibration_selection` 컬럼이 calibration에서 그 설정을 어떻게 얻었는지 알려줍니다.

| 값 | 의미 | 비교에 사용 |
|---|---|---|
| `WITHIN_TOLERANCE` | 튜닝에서 목표 ±0.01 안에 드는 후보를 찾음 | `target_met=true`일 때만 O |
| `CLOSEST_AVAILABLE` | 구간에 드는 후보가 없어 가장 가까운 값을 선택 | **X** |
| `EXPLICIT_PARAMETERS` | 사용자가 검색 파라미터를 직접 지정 | 조건부 |

`CLOSEST_AVAILABLE` 행끼리, 또는 그 행과 `WITHIN_TOLERANCE` 행을 나란히 두고
"어느 DB가 빠르다"고 말하면 안 됩니다. 서로 다른 Recall의 속도를 비교하는 것이기 때문입니다.
calibration과 evaluation 값이 달라질 수 있으므로 `calibration_selection`뿐 아니라 `target_met`도
반드시 확인합니다.

## 목표를 못 맞추는 경우

후보 사다리가 기하급수(`10, 20, 40, 80, ...`)라서 ±0.01 밴드를 지나칠 수 있습니다.
더 근본적으로, **최소 후보에서 이미 목표를 넘어버리는** DB가 있습니다.

```text
OpenSearch ef_search=10  → 비교 Recall 0.9296   ← 0.80도 0.90도 도달 불가
```

후보는 `candidate >= topK` 조건으로 걸러지므로 10 밑으로 내려갈 수 없습니다.
이 경우 `ef`가 아니라 `M` / `ef_construction`을 낮춰야 합니다.

현재 이 부분은 미해결입니다. [../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)를 봅니다.

## 필터별 Recall

결과에는 `filtered_recall`과 `unfiltered_recall`이 따로 기록됩니다.

`filtered_recall`이 `unfiltered_recall`보다 뚜렷이 낮으면,
ANN으로 뽑은 뒤 필터를 적용하는 post-filtering 때문에 Top-K를 못 채우고 있다는 신호입니다.

## 관련 문서

- [exact-vs-ann.md](exact-vs-ann.md)
- [hnsw.md](hnsw.md)
- [../03-benchmark-design/ground-truth.md](../03-benchmark-design/ground-truth.md)
