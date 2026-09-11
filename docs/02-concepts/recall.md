# Recall@K

이 실험에서 **Exact Top-K를 얼마나 재현했는지 나타내는 검색 품질 지표**입니다.

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
```

## exact 정답이 0개인 질의는 Recall에서 제외합니다

필터가 어떤 문서와도 매칭되지 않으면 재현할 순위 자체가 없습니다. 이런 질의를 Recall 평균에
1.0으로 섞으면 두 가지 문제가 생깁니다.

- **점수가 부풀려집니다.** 제공 데이터의 evaluation 필터 질의 20개 중 6개가 여기 해당해,
  1,000요청 측정 단위의 필터 검색 100건 중 30건이 무조건 만점이 됩니다. 필터 Recall `1.0000`의 30%가
  측정이 아니라 규칙이 만든 값입니다.
- **필터 결함을 놓칩니다.** 매칭되는 문서가 없는데 DB가 행을 반환하면 그건 필터 버그인데,
  1.0으로 채점하면 드러나지 않습니다.

그래서 이런 질의는 Recall 평균에서 빼고 **빈 결과를 반환했는지 별도 기록**합니다. 이 검사로 전체 측정 점을 제거하지 않습니다.

```java
if (recallCalculator.hasGroundTruth(exact)) {
    scores.recordScored(recallCalculator.recallAtK(exact, approximate, topK));
} else {
    scores.recordEmptyGroundTruth(recallCalculator.returnedNothing(approximate));
}
```

결과 파일에 구간별로 세 값이 남습니다.

| 컬럼 | 의미 |
|---|---|
| `*_scored_queries` | Recall 평균에 실제로 들어간 검색 수 |
| `*_empty_ground_truth_queries` | exact 정답이 비어 있어 제외된 검색 수 |
| `*_empty_ground_truth_violations` | 그중 행을 반환한 위반 검색 수 |

`*_recall`은 `*_scored_queries`에 대한 평균입니다. `*_empty_ground_truth_violations`가 0이 아니면
그 제품의 필터가 exact 검색과 다른 문서 집합을 보고 있다는 뜻이므로 Recall보다 먼저 확인합니다.

## Recall이 아닌 것

Recall@K는 **exact 검색과 얼마나 같은가**입니다.
"좋은 문서를 찾았는가"가 아닙니다.

embedding이 나쁘면 exact 검색 결과 자체가 엉망이고, ANN이 그걸 그대로 재현하면 Recall은 1.0입니다.
의미 품질은 `data/qrels.tsv`로 따로 평가해야 하며, 그 지표는 Vector DB가 아니라
embedding과 chunking 품질의 영향을 함께 받습니다.

## 품질 참고선과 전체 측정

Recall@10 0.90과 0.95는 품질 참고 수준입니다. 허용 범위와 합격·탈락 판정으로 사용하지 않습니다. 검색 파라미터를 바꿀 때마다 Recall·latency·QPS·CPU·RAM을 측정하고 전부 보존합니다.

산포도의 X축은 전체 evaluation p95, Y축은 전체 `actual_recall`입니다. 0.90과 0.95는 수평 참고선입니다. `comparison_recall`은 무필터 세부 분석용 보조값으로 남습니다.

가령 ef=64의 Recall 0.943과 ef=128의 Recall 0.961은 모두 유효한 점입니다. 0.95 이상 영역을 설명할 대표값으로 0.961을 골라도 0.943은 전체 분석에 남습니다. 최저 파라미터에서 이미 0.95를 넘는 구성도 그대로 측정하며, 참고선에 맞추려고 품질을 낮출 필요가 없습니다.

반복의 목적은 같은 파라미터에서 latency·QPS·CPU·RAM의 변동을 확인하는 것입니다. Recall도 실제 관측값과 함께 남기지만 그 값으로 반복을 통과·탈락시키지 않습니다. 파라미터가 커졌는데 Recall이 내려가도 보정하거나 버리지 않고 실제 변동으로 표시합니다.
## 필터별 Recall

결과에는 `filtered_recall`과 `unfiltered_recall`이 따로 기록됩니다.

`filtered_recall`이 `unfiltered_recall`보다 낮으면 필터 경로와 반환 건수를 확인합니다. post-filtering, 탐색 폭, 필터 처리 방식 등이 원인 후보이며 두 Recall 값만으로 원인을 확정하지 않습니다.

이번 evaluation 200개 중 180개는 무필터, 20개는 필터 질의입니다. 정답이 빈 필터 질의 6개를 제외한 194개가 한 번의 질의 집합 실행에서 Recall 평균에 들어갑니다. 지연과 QPS는 200개 모두를 포함합니다. 최소 5초를 채우기 위한 실제 요청 수는 점마다 다르므로 저장된 `query_executions`와 `*_scored_queries`를 확인합니다.

## 관련 문서

- [exact-vs-ann.md](exact-vs-ann.md)
- [hnsw.md](hnsw.md)
- [../03-benchmark-design/ground-truth.md](../03-benchmark-design/ground-truth.md)
