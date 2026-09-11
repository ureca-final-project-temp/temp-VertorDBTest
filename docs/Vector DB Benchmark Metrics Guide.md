# Vector DB Benchmark Metrics Guide

본 문서는 Vector DB 비교 실험에서 사용하는 주요 지표의 의미와 해석 기준을 설명한다.

## 1. Recall@K

Recall@K는 ANN(Approximate Nearest Neighbor) 검색이 Exact Search의 Top-K 결과를 얼마나 잘 재현했는지를 나타낸다.

계산식:

```text
Recall@K
= |Exact Top-K ∩ ANN Top-K| / K
```

예를 들어 Exact Search의 Top-10 중 ANN Search가 9개를 동일하게 찾았다면:

```text
Recall@10 = 9 / 10 = 0.9
```

Recall@K는 결과의 순서를 고려하지 않는다.

```text
Exact : A B C D E
ANN   : E D C B A
```

위 경우 Exact Top-5의 모든 결과를 ANN이 찾았으므로:

```text
Recall@5 = 1.0
```

이다.

### Recall 목표 구간

본 실험에서는 다음 구간을 비교 기준으로 사용한다.

```text
Recall@10 ≈ 0.90
Recall@10 ≈ 0.95
Recall@10 ≈ 0.99
```

이 값들은 절대적인 서비스 품질 기준이 아니라 ANN의 검색 품질과 성능 간 trade-off를 비교하기 위한 측정 지점이다.

일반적으로 탐색 범위를 넓히면 Recall은 증가하지만 latency와 CPU 사용량 역시 증가할 수 있다.

예:

```text
Recall       p95 latency
0.90          3 ms
0.95          5 ms
0.99         15 ms
```

이 경우 Recall 0.95에서 0.99로 증가시키기 위해 상당한 추가 비용이 발생한다고 해석할 수 있다.

---

## 2. Exact Search

Exact Search는 Query Vector와 전체 Document Vector를 직접 비교하여 실제 가장 가까운 Top-K를 계산한다.

본 실험에서는 Exact Search를 두 가지 목적으로 사용한다.

첫 번째는 ANN Recall 계산을 위한 Ground Truth 생성이다.

```text
Exact Search
→ Ground Truth Top-K

ANN Search
→ Approximate Top-K

두 결과 비교
→ Recall@K
```

두 번째는 동일 DB 내부에서 ANN Index 사용 전후의 검색 성능을 비교하기 위한 기준선이다.

예:

```text
Exact p95 = 120 ms
HNSW p95  =   8 ms
Recall@10 = 0.96
```

이는 해당 조건에서 HNSW가 Exact Search 대비 검색 비용을 크게 줄이면서 약 0.96의 Recall을 달성했다는 의미다.

---

## 3. Latency

Latency는 검색 요청 한 건이 처리되는 데 걸리는 시간이다.

본 실험에서는 평균값보다 percentile 기반 지표를 우선적으로 사용한다.

### p50

전체 요청 중 50%가 이 시간 이하에 완료된다.

일반적인 요청의 체감 성능을 나타내는 값이다.

### p95

전체 요청 중 95%가 이 시간 이하에 완료된다.

본 실험의 주요 latency 비교 지표로 사용한다.

평균값보다 느린 요청과 tail latency의 영향을 더 잘 반영한다.

### p99

전체 요청 중 99%가 이 시간 이하에 완료된다.

일부 매우 느린 요청이 존재하는지 확인할 때 사용한다.

예:

```text
p50 = 3 ms
p95 = 7 ms
p99 = 18 ms
```

평소 요청은 빠르지만 일부 요청에서 latency가 크게 증가하고 있음을 의미한다.

---

## 4. QPS

QPS(Queries Per Second)는 Vector DB가 초당 처리할 수 있는 검색 요청 수를 나타낸다.

```text
QPS = 처리한 Query 수 / 실행 시간
```

QPS가 높을수록 동일 시간 동안 더 많은 검색 요청을 처리할 수 있다.

단, QPS는 반드시 동일한 조건에서 비교해야 한다.

다음 조건이 다르면 직접 비교하지 않는다.

```text
Concurrency
Recall
Top-K
Dataset
Hardware
Resource Limit
```

예를 들어 Recall 0.90에서 QPS가 높더라도 Recall 0.99에서 급격히 감소할 수 있으므로 Recall과 함께 해석해야 한다.

---

## 5. CPU Usage

검색 중 Vector DB가 사용하는 CPU 자원의 비율을 측정한다.

동일 Recall과 QPS 조건에서 CPU 사용량이 낮다면 더 높은 자원 효율을 가진다고 해석할 수 있다.

단, CPU Usage만 단독으로 비교하지 않는다.

예:

```text
DB A
CPU = 90%
QPS = 2000

DB B
CPU = 50%
QPS = 500
```

DB B의 CPU 사용량이 낮더라도 처리량 역시 낮으므로 단순히 더 효율적이라고 판단할 수 없다.

---

## 6. Memory Usage

Vector DB 프로세스 또는 Container가 사용하는 RAM 크기를 측정한다.

특히 HNSW와 같은 ANN Index는 Index 구조를 메모리에 유지하기 때문에 Vector 수가 증가할수록 Memory Usage가 중요한 비교 항목이 된다.

동일 Dataset과 동일 Recall 수준에서 다음을 비교한다.

```text
Peak Memory
Average Memory
Search 중 Memory Usage
Index 생성 후 Memory Usage
```

---

## 7. Disk Usage

Vector 데이터와 ANN Index가 사용하는 저장 공간을 측정한다.

가능하면 다음을 분리해서 기록한다.

```text
Raw Vector Data Size
Index Size
Total DB Storage Size
```

동일한 검색 성능을 제공하더라도 Index Size가 크게 다를 수 있으므로 대규모 Dataset에서 중요한 지표다.

---

## 8. Index Build Time

Dataset 적재 후 ANN Index를 생성하는 데 필요한 시간을 측정한다.

Index Build Time은 실시간 Query 성능과는 별개지만 다음 상황에서 중요하다.

```text
초기 데이터 구축
Embedding Model 변경
Chunking 변경
Vector 재생성
Index Rebuild
Migration
```

검색 속도가 빠르더라도 Index Build에 지나치게 많은 시간이 필요한 경우 운영 비용이 증가할 수 있다.

---

## 9. Insert / Upsert Throughput

초당 삽입 또는 갱신할 수 있는 Vector Record 수를 나타낸다.

```text
records / second
```

문서 변경이 자주 발생하는 서비스에서는 Search 성능뿐 아니라 Vector 갱신 성능도 중요하다.

---

## 10. 주요 결과 해석 방식

Vector DB는 하나의 지표로 평가하지 않는다.

본 실험의 핵심 비교 기준은 다음과 같다.

```text
동일 Recall
    ↓
Latency 비교
QPS 비교
CPU 비교
RAM 비교
Disk 비교
```

예:

```text
DB A
Recall@10 = 0.95
p95       = 5 ms
QPS       = 900
RAM       = 5 GB

DB B
Recall@10 = 0.95
p95       = 7 ms
QPS       = 750
RAM       = 2 GB
```

이 결과에서 DB A는 latency와 throughput에 강점이 있지만 더 많은 Memory를 사용한다.

DB B는 검색 성능이 다소 낮지만 Memory 효율이 더 높다.

따라서 특정 DB가 절대적으로 우수하다고 판단하기보다 서비스 요구사항에 따라 trade-off를 해석해야 한다.

---

## 11. 주요 비교 그래프

메인 시각화는 Recall-Latency Scatter Plot을 사용한다.

```text
X축 = p95 Latency
Y축 = Recall@10
```

왼쪽으로 갈수록 빠르고 위로 갈수록 Recall이 높다.

따라서 동일한 조건에서는 왼쪽 위에 위치할수록 검색 품질 대비 latency가 우수한 구성으로 해석할 수 있다.

추가적으로 다음 그래프를 사용할 수 있다.

```text
Recall vs QPS
Recall vs RAM
Recall vs CPU
Dataset Size vs Latency
Dataset Size vs Memory
```

---

## 12. 결과 해석 시 주의사항

본 Benchmark 결과는 특정 Dataset, Hardware, Docker Resource Limit, Query Set 및 Vector DB 버전에서 측정한 상대적인 결과다.

따라서 다음과 같은 표현은 지양한다.

```text
Qdrant가 항상 pgvector보다 빠르다.
```

대신 다음과 같이 표현한다.

```text
본 테스트 환경에서 Recall@10 약 0.95를 만족하는 설정 기준으로
Qdrant가 pgvector보다 낮은 p95 latency를 기록했다.
```

또한 Vector DB별 SDK, Protocol, ANN 구현 및 내부 저장 구조가 다르기 때문에 본 실험은 ANN 알고리즘 자체만을 분리한 성능 비교가 아니라 실제 Spring Boot 애플리케이션에서 Vector DB를 사용하는 전체 검색 경로의 상대 성능 비교로 해석한다.