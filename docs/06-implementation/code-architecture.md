# Code Architecture

## 패키지 구조

```text
com.myapp
├─ api/                     REST 엔드포인트
│  ├─ BenchmarkController      POST /api/benchmarks/run
│  ├─ SearchController         GET /api/search/store, POST /api/search
│  └─ ApiExceptionHandler
│
├─ application/             유스케이스
│  ├─ document/                ChunkingService, DocumentService
│  ├─ embedding/               EmbeddingService
│  └─ search/                  VectorSearchService
│
├─ domain/                  DB 중립 모델
│  ├─ document/                Document, DocumentChunk
│  └─ vector/                  VectorDocument, VectorSearchRequest/Result,
│                              VectorFilter, BenchmarkQuery, DistanceMetric
│
├─ port/                    계약
│  ├─ VectorStore              적재·검색·건수
│  ├─ VectorIndexManager       생성·삭제·준비 대기·파라미터
│  └─ EmbeddingProvider
│
├─ infrastructure/
│  ├─ vector/<db>/             DB별 어댑터 5종
│  ├─ vector/http/             JsonHttpClient, VectorHttpSupport
│  ├─ embedding/               OllamaEmbeddingClient, FixedEmbeddingProvider
│  └─ rdb/postgres/            Source of Truth 저장 계층
│
├─ benchmark/               측정 엔진
├─ dataset/                 JSONL 로더
└─ tools/                   EmbeddingDatasetGenerator, FilterSelectivityWorkloadGenerator,
                            BenchmarkChartGenerator
```

## 개념 → 코드 위치

| 알고 싶은 것 | 파일 |
|---|---|
| 벤치마크 전체 흐름 | `benchmark/BenchmarkRunner.java` |
| 정답지 계산 | `benchmark/ExactSearchEngine.java` |
| Recall@K 공식 | `benchmark/RecallCalculator.java` |
| 전체 검색 파라미터 그리드 확장 | `benchmark/SearchParameterSweep.java` |
| 지연시간 percentile, 필터/무필터 분리 | `benchmark/LatencyCollector.java` |
| 구간별 결과 묶음 | `benchmark/QuerySegment.java` |
| CPU·메모리·디스크 샘플링 | `benchmark/ResourceCollector.java` |
| 결과 JSON/CSV/SVG 출력 | `benchmark/ResultWriter.java` |
| 동일 파라미터 반복 통계 | `benchmark/BenchmarkSummary.java` |
| 실제 점과 품질 참고선의 산포도 | `benchmark/RecallLatencyPlot.java` |
| 저장된 결과의 산포도 재생성 | `tools/BenchmarkChartGenerator.java` |
| 재현 환경 수집 | `benchmark/BenchmarkEnvironmentCollector.java` |
| PostgreSQL 원본 스냅샷 동기화 | `infrastructure/rdb/postgres/BenchmarkSourceOfTruthSynchronizer.java` |
| 설정값 | `benchmark/BenchmarkProperties.java` |
| 벡터 JSONL 파싱 | `dataset/VectorDatasetLoader.java`, `dataset/JsonlSupport.java` |
| 질의 + 필터 로딩 | `dataset/QuerySetLoader.java` |
| calibration/evaluation 고정 분할 | `benchmark/QueryPartitioner.java` |
| Milvus drift 진단 모델 | `benchmark/StabilityDiagnostics.java` |
| 임베딩 생성기 | `tools/EmbeddingDatasetGenerator.java` |
| 필터 1%/10%/50% 생성기 | `tools/FilterSelectivityWorkloadGenerator.java` |

## 프로필로 DB를 고르는 방식

Spring 프로필이 `vector.store.type`을 정하고, 각 `<Db>Config`가 그 값에 반응합니다.

```java
@ConditionalOnProperty(prefix = "vector.store", name = "type", havingValue = "qdrant")
public class QdrantConfig { ... }
```

기본 `application.yml`은 `vector.store.type: none`이라 아무 스토어도 활성화되지 않습니다.
그래서 `BenchmarkRunner`는 `ObjectProvider`로 받아 없으면 명확한 메시지로 실패합니다.

```java
VectorStore store = storeProvider.getIfAvailable();
if (store == null) throw new IllegalStateException("No vector store is active. Enable a vector DB Spring profile.");
```

**한 프로세스에는 항상 스토어가 0개 또는 1개입니다.** 두 DB를 동시에 측정할 수 없는 구조입니다.

## 불변 규칙

- `domain`과 `port`는 특정 DB에 의존하지 않습니다.
- Ground Truth는 `VectorStore`를 거치지 않습니다.
- PostgreSQL Source of Truth 동기화와 Flyway migration은 검색 타이머 밖입니다.
- 측정 타이머는 `store.search()`만 감쌉니다. 이 경계를 넓히지 않습니다.
- `BenchmarkResult`는 record이며 필드 추가 시 `ResultWriter`의 CSV 헤더와 포맷을 함께 고칩니다.
- 원시 JSON과 산포도 점은 실제 측정값입니다. 집계는 같은 파라미터의 실제 반복을 요약하며 평균을 새 실측점으로 표시하지 않습니다.
- 미지원 원시 자원값은 `-1`, 집계의 유효 표본이 없으면 `null`과 표본 수를 기록합니다.

## 테스트

```powershell
.\gradlew.bat test
```

| 테스트 | 검증 대상 |
|---|---|
| `ExactSearchEngineTest` | 필터 적용 순서, Top-K 정렬 |
| `RecallCalculatorTest` | 분모 처리, 중복 제거 |
| `BenchmarkRunnerTest` | 전체 그리드·반복, 완전한 질의 단위의 최소 시간 확장, 중간 오류 전 결과 보존 |
| `BenchmarkSummaryTest` | 같은 검색 설정끼리의 반복 통계와 미지원 자원값 처리 |
| `BenchmarkScenarioTest` | 그리드·고정 파라미터 입력 계약 |
| `LatencyCollectorTest` | percentile, 필터/무필터 분리 |
| `QuerySegmentTest` | 구간 집계, 빈 구간 |
| `ResourceCollectorTest` | docker stats 단위, baseline 제외, 수집 구간의 검색 구간 포함 조건 |
| `ResultWriterTest` | 점별 원시 보존, CSV·집계·산포도 갱신과 형식 |
| `QueryPartitionerTest` | query type 층화, 고정 100/200 분할 |
| `VectorIndexContractTest` | 14개 구성의 adapter engine/index/search 파라미터 계약 |
| `QdrantIndexManagerTest` | exact-scan 대기 생략 경로에서도 payload index 검증 |
| `VectorDatasetLoaderTest`, `QuerySetLoaderTest` | JSONL 필드 별칭, 필터 병합 |

이번 실행 전 자동 테스트 42개가 통과했고, 이후 실제 DB로 372개 측정을 완료했습니다. 테스트 목록과 실행 근거는 [고정 산출물 안내](../07-results/assets/sweep-20260911-220549/README.md)에 있습니다.

DB 어댑터의 HTTP 계약 일부는 mock HTTP 단위 테스트로 검증하고, 실제 제품 API와 비동기
준비 상태는 컨테이너 smoke/full benchmark로 검증합니다.
[../04-quickstart/run-benchmark.md](../04-quickstart/run-benchmark.md)를 봅니다.

## 관련 문서

- [vector-store.md](vector-store.md)
- [adapter-policy.md](adapter-policy.md)
- [result-format.md](result-format.md)
