# Common Issues

증상 → 진단 → 해결 → 재검증 순서로 정리했습니다.

---

## No vector store is active

```text
IllegalStateException: No vector store is active. Enable a vector DB Spring profile.
```

기본 `application.yml`은 `vector.store.type: none`이라 어떤 어댑터도 활성화되지 않습니다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=qdrant"
```

### 확인

```powershell
Invoke-RestMethod http://localhost:8080/api/search/store
# expected: database = qdrant
```

---

## Vector count mismatch

```text
IllegalStateException: Vector count mismatch: expected 10000 but store has 9873
```

적재가 일부 실패했습니다. 이 상태로 측정하면 Recall 저하로 오인하게 되므로 즉시 중단합니다.

### 진단

```powershell
docker compose logs --tail=100 qdrant
Get-Content benchmark-result/<dir>/logs/<profile>-application-error.log -Tail 50
```

흔한 원인: 메모리 상한 도달, bulk 요청 실패, 컨테이너 재시작.

### 해결

`rebuildAndLoad: true`로 다시 실행합니다. 반복되면 `benchmark.upsert-batch-size`를 낮춥니다.

```powershell
$env:UPSERT_BATCH_SIZE = "128"
```

---

## Qdrant payload index is missing

```text
IllegalStateException: Qdrant payload index is missing for [metadata.tenant_id];
filtered search would fall back to a full scan
```

**이것은 버그가 아니라 의도된 차단입니다.** payload index 없이 측정하면
필터 질의가 full scan이 되어 결과가 무효가 됩니다.

### 진단

```powershell
Invoke-RestMethod http://localhost:6333/collections/benchmark_chunks |
  Select-Object -ExpandProperty result | Select-Object payload_schema
```

### 해결

`application-qdrant.yml`의 `payload-index-fields`에 필터 키가 선언돼 있는지 확인하고
컬렉션을 다시 만듭니다(`rebuildAndLoad: true`).

자세한 배경은 [../05-databases/qdrant.md](../05-databases/qdrant.md)를 봅니다.

---

## PostgreSQL source snapshot does not match

```text
IllegalStateException: PostgreSQL source snapshot does not match the benchmark input;
run with rebuildAndLoad=true
```

`documents`, `document_chunks`, `benchmark_dataset_state`의 해시 또는 건수가 현재 vector
JSONL과 다릅니다. 변경된 원본을 검색 DB에 조용히 재사용하지 않도록 막은 것입니다.

### 해결

입력을 의도적으로 교체한 것이 맞는지 먼저 확인한 뒤 `rebuildAndLoad: true`로 실행합니다.
동기화가 끝나면 PostgreSQL에는 원문 200건과 chunk 10,000건이 있어야 합니다.

---

## Flyway가 non-empty schema에서 시작하지 못한다

```text
Found non-empty schema(s) "public" but no schema history table
```

이 프로젝트는 `spring.flyway.baseline-version=0`과 `baseline-on-migrate=true`로 기존 개발
볼륨을 인수합니다. 해당 설정을 지웠거나 프로필에서 덮어쓰지 않았는지 확인합니다.
baseline을 1로 올리면 기존 볼륨에서 V1 Source of Truth 테이블 생성을 건너뛸 수 있으므로
임의로 바꾸지 않습니다.

---

## Resource budget mismatch

```text
Resource budget mismatch for qdrant: expected cpuNano=4000000000,... actual cpuNano=0,...
```

`docker inspect`가 보고한 실제 상한이 선언과 다릅니다. 측정을 시작하지 않습니다.

### 진단

```powershell
docker inspect vector-qdrant --format `
  'cpuNano={{.HostConfig.NanoCpus}},mem={{.HostConfig.Memory}},swap={{.HostConfig.MemorySwap}}'
```

### 해결

컨테이너가 예전 설정으로 떠 있는 경우가 대부분입니다. 다시 만듭니다.

```powershell
docker compose --profile qdrant up -d --force-recreate qdrant
```

스크립트를 거치지 않고 수동으로 `docker compose up`을 했다면
`VECTOR_CPU_LIMIT` / `VECTOR_MEMORY_LIMIT` 환경변수가 설정되지 않아 기본값이 적용됩니다.

---

## 자원 값이 전부 -1

`cpu_percent`, `peak_memory_bytes`, `disk_write_bytes`가 모두 `-1`이면
`docker stats`를 한 번도 성공하지 못한 것입니다.

### 진단

```powershell
docker stats --no-stream --format "{{.CPUPerc}}|{{.MemUsage}}|{{.BlockIO}}" vector-qdrant
```

### 해결

- Docker 소켓 권한 확인
- 프로필 YAML의 `benchmark.container-names`가 실제 컨테이너 이름과 일치하는지 확인

```yaml
benchmark.container-names: [vector-qdrant]
```

---

## CPU가 0.01% 같은 비현실적인 값

`docker stats`는 약 1초 집계창을 가집니다. 전체 측정 구간이 그보다 짧으면
CPU 평균이 심하게 과소 집계됩니다.

수십 ms짜리 smoke run에서는 정상입니다. **그 행을 자원 비교에서 제외합니다.**

본실험에서 나타나면 측정 요청 수를 늘립니다(`measurementIterations`).

---

## Existing CSV schema is incompatible

```text
IllegalStateException: Existing CSV schema is incompatible;
use a new benchmark result directory: ...
```

결과 CSV 컬럼이 바뀌었는데 예전 디렉터리에 이어 쓰려 했습니다.

### 해결

새 디렉터리를 지정합니다.

```powershell
.\scripts\run-all-benchmarks.ps1 -ResultDirectory benchmark-result/rerun-01
```

---

## Recall이 0에 가깝다

거의 항상 **id 불일치**입니다. Ground Truth의 id와 DB가 반환한 id가 다릅니다.

### 진단

```powershell
Get-Content benchmark-result/<dir>/raw/ground-truth-top10.jsonl -TotalCount 1
```

여기의 id와 `POST /api/search` 응답의 `id`를 비교합니다.

### 원인

Qdrant와 Weaviate는 내부 id로 UUID를 씁니다. 어댑터가 변환된 UUID를 반환하면 Recall이 0이 됩니다.
원본 id는 payload/property에서 읽어야 합니다.
[../06-implementation/adapter-policy.md](../06-implementation/adapter-policy.md)의 3번 규칙을 봅니다.

---

## Recall이 1.0에 가깝고 latency가 비정상적으로 낮다

HNSW가 아니라 exact scan을 측정하고 있을 가능성이 큽니다.

| DB | 확인 |
|---|---|
| Qdrant | `indexed_vectors_count`가 전체 건수인지 |
| Milvus | `indexState=Finished`, `pendingRows=0`인지 |
| pgvector | `enable_seqscan=off`가 적용됐는지 (`force-index-scan: true`) |

`awaitReady()`가 이 장벽을 담당하지만, 임계값 설정이 바뀌면 우회될 수 있습니다.

---

## ef를 올려도 latency가 변하지 않는다

탐색 파라미터가 적용되지 않았거나, 그 경로가 HNSW를 타지 않고 있습니다.

1. 어댑터가 파라미터를 실제로 전송하는지 확인합니다.
2. Weaviate는 요청별이 아니라 스키마 설정입니다. `configureSearch()`가 호출됐는지 확인합니다.
3. **특정 percentile만 무반응이면 필터 인덱스 누락을 의심합니다.**
   실제 사례는 [../07-results/analysis.md](../07-results/analysis.md)에 있습니다.

---

## Milvus가 뜨지 않는다

etcd와 MinIO가 healthy가 된 뒤에야 기동합니다. `start_period`가 90초입니다.

```powershell
docker compose --profile milvus ps
docker compose --profile milvus logs --tail=100 milvus
```

메모리 상한(6656 MiB)에 걸려 OOM으로 죽는 경우가 있습니다.

```powershell
docker inspect vector-milvus --format '{{.State.OOMKilled}}'
```

---

## OpenSearch가 yellow에서 멈춘다

single-node라 replica가 배치되지 않아 `yellow`가 정상입니다.
스크립트도 `wait_for_status=yellow`로 기다립니다.

`red`이면 로그를 확인합니다.

```powershell
Invoke-RestMethod 'http://localhost:9200/_cluster/health?level=indices'
```

---

## 임베딩 생성이 중간에 멈춘다

checkpoint 기반이라 다시 실행하면 이어서 생성합니다.

```powershell
.\gradlew.bat generateEmbeddings
```

```text
Resuming data/embeddings/document-vectors.jsonl.partial at record 5248
```

`Partial output has no checkpoint` 또는 `Final and partial embedding outputs coexist`가 나오면
`.partial`과 `.checkpoint.json`을 지우고 처음부터 다시 생성합니다.

---

## 그래도 실패하면

수집할 정보:

```powershell
docker compose ps -a
docker compose logs --tail=200
Get-Content benchmark-result/<dir>/logs/*-application-error.log -Tail 100
Get-Content benchmark-result/<dir>/raw/benchmark-*.json |
  ConvertFrom-Json | Select-Object -ExpandProperty environment
```
