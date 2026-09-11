# Experiment Design

## 답하려는 질문

> 검색 품질(Recall@10)을 같은 수준으로 맞췄을 때,
> 어떤 Vector DB가 더 낮은 지연시간과 적은 자원으로 검색을 수행하는가

## 설계 원칙

1. **품질을 먼저 고정하고 속도를 측정한다.** ANN은 탐색 폭을 줄이면 항상 빨라지므로 그 반대는 무의미합니다.
2. **정답은 DB 밖에서 만든다.** Ground Truth는 Java brute-force로 계산합니다.
3. **입력은 파일로 고정한다.** 같은 float를 다섯 DB에 재사용하고 SHA-256으로 확인합니다.
4. **자원 상한을 검증한다.** 선언만 하지 않고 측정 직전에 `docker inspect`로 실제 값을 확인합니다.
5. **목표를 못 맞췄으면 그렇다고 기록한다.** 숨기지 않고 `CLOSEST_AVAILABLE`로 표시합니다.
6. **비교 모집단을 섞지 않는다.** 목표 Recall 튜닝과 ANN 비교는 무필터 질의를 사용하고,
   필터 질의는 별도 지표로 판정합니다.

## 시나리오 구조

한 시나리오는 하나의 목표 Recall에 대응합니다.

```json
{
  "database": "",
  "indexType": "hnsw",
  "targetRecall": 0.90,
  "topK": 10,
  "concurrency": 10,
  "warmupIterations": 1,
  "measurementIterations": 5,
  "searchParameters": {}
}
```

| 필드 | 의미 |
|---|---|
| `database` | 비우면 활성 스토어를 그대로 사용. 값이 있으면 불일치 시 실행 거부 |
| `targetRecall` | 목표 Recall@K |
| `concurrency` | 측정 스레드 수 |
| `warmupIterations` | 측정 전 전체 질의를 도는 횟수 |
| `measurementIterations` | 측정 시 전체 질의를 도는 횟수 |
| `searchParameters` | 비우면 자동 튜닝, 값이 있으면 그대로 사용 |

본실험은 목표 3개(0.80 / 0.90 / 0.95)를 한 요청에 담습니다.

## 실행 순서

```text
1. 입력 로드·해시 검증
   └─ PostgreSQL Source of Truth 동기화 (200 documents / 10,000 chunks)
2. rebuildAndLoad = true
   ├─ indexManager.rebuild()          drop → create
   ├─ store.upsert(...)               배치 256건
   └─ indexManager.awaitReady(...)    비동기 인덱싱 완료 대기
3. store.count() == chunk 수 검증      틀리면 즉시 중단
4. Ground Truth 계산 (topK별 1회, 캐시)
5. 시나리오마다
   ├─ 무필터 질의 자동 튜닝 (같은 실행 조건의 목표끼리 후보 측정값 공유)
   ├─ configureSearch(선택된 파라미터)
   ├─ warm-up
   └─ 본 측정  ← 여기만 타이머와 자원 샘플링 적용
6. JSON + CSV + SVG 출력
```

`time_to_index_ready_ms`와 `upsert_ms`는 한 실행의 **첫 시나리오에만** 기록됩니다.
2·3번째 시나리오는 같은 인덱스를 재사용하므로 0입니다.

## 부하 조건

| 항목 | 값 |
|---|---|
| 동시성 | 10 (고정 스레드 풀) |
| warm-up | 전체 질의 1회 |
| 측정 | 전체 질의 5회 |
| DB·목표당 측정 요청 | 300 × 5 = 1,500건 |
| DB당 본실험 측정 요청 | 1,500 × 3 = 4,500건 |

자동 튜닝은 후보 9개에 대해 무필터 270개 질의로 같은 warm-up·동시성·반복을 수행합니다.
같은 실행 조건을 가진 목표들은 이 후보 측정값을 공유합니다.

## 실행 격리

- **한 번에 하나의 DB만 기동합니다.** 프로필로 분리하고 측정 후 컨테이너를 stop합니다.
- PostgreSQL은 Source of Truth로 항상 떠 있지만 pgvector 프로필을 제외하면 측정 대상이 아닙니다.
- 앱과 DB 캐시 조건을 통일한 뒤 다음 DB로 넘어갑니다.

## 재현 조건

결과 JSON의 `environment`에 다음이 기록됩니다.

- 입력 3개 파일의 SHA-256
- OS / CPU / 논리 코어 수 / 물리 메모리
- Java 버전, Spring Boot 버전, Docker 서버 버전
- 선언한 자원 예산과 `docker inspect`가 보고한 실제 컨테이너 상한
- PostgreSQL Source of Truth의 입력 SHA-256, 원문 수, 청크 수, 이번 실행의 동기화 여부

이 값이 다르면 서로 다른 실험입니다.

## 관련 문서

- [controlled-variables.md](controlled-variables.md)
- [dataset-and-queryset.md](dataset-and-queryset.md)
- [ground-truth.md](ground-truth.md)
- [limitations.md](limitations.md)
