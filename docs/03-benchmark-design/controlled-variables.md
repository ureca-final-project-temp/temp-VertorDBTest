# Controlled Variables

비교가 성립하려면 아래 항목이 전 DB에서 같아야 합니다.

## 고정 항목

| 변수 | 값 | 강제 방법 |
|---|---|---|
| 벡터 파일 | 동일 1024차원 JSONL | `environment`에 SHA-256 기록 |
| 차원 | 1024 | `validateDimensions()` 불일치 시 실행 거부 |
| distance metric | COSINE | 스토어 metric과 불일치 시 실행 거부 |
| 인덱스 종류 | HNSW | 시나리오 `indexType`과 불일치 시 실행 거부 |
| `M` | 16 | 프로필 YAML |
| `ef_construction` | 128 | 프로필 YAML |
| Top-K | 10 | 시나리오 |
| 동시성 | 10 | 시나리오 |
| warm-up / 측정 반복 | 1 / 5 | 시나리오 |
| 자원 상한 | 합계 4 vCPU / 8 GiB, swap 금지 | `docker inspect` 검증, 불일치 시 중단 |
| 제품 버전 | 표 참조 | `docker-compose.yml` 환경변수 |

## 제품 버전

| 구성 | 버전 |
|---|---:|
| Java | 21 |
| Spring Boot | 4.1.1 |
| PostgreSQL / pgvector | 17 / 0.8.6 |
| Qdrant | 1.19.0 |
| Weaviate | 1.39.3 |
| Milvus | 3.0.1 |
| OpenSearch | 3.8.0 |

비교 실행 중에는 버전을 바꾸지 않습니다.

## 자원 예산

기본값은 **대상별 합계 4 vCPU / 8 GiB**입니다.

| 프로필 | 컨테이너 | CPU | 메모리 |
|---|---|---:|---:|
| pgvector | `vector-postgres` | 4.0 | 8 GiB |
| qdrant | `vector-qdrant` | 4.0 | 8 GiB |
| weaviate | `vector-weaviate` | 4.0 | 8 GiB |
| opensearch | `vector-opensearch` | 4.0 | 8 GiB |
| milvus | `vector-milvus` | 3.0 | 6656 MiB |
| | `vector-milvus-etcd` | 0.5 | 512 MiB |
| | `vector-milvus-minio` | 0.5 | 1 GiB |
| | **합계** | **4.0** | **8 GiB** |

Milvus는 보조 서비스가 필요하므로 그 몫을 예산 **안에서** 나눕니다. 예산을 더 주지 않습니다.

`memswap_limit`을 메모리 상한과 같게 설정해 swap을 쓸 수 없게 합니다.
swap이 열려 있으면 메모리 압박이 지연시간으로 드러나지 않고 조용히 디스크로 새어 나갑니다.

### 검증

`scripts/run-all-benchmarks.ps1`이 DB 기동 직후 검사합니다.

```powershell
$actualNanoCpus      -ne $expectedNanoCpus     -or
$actualMemoryBytes   -ne $DatabaseMemoryLimitBytes -or
$actualMemorySwapBytes -ne $DatabaseMemoryLimitBytes
    → throw
```

선언과 실제가 다르면 **측정을 시작하지 않습니다.**

### 예산 변경

```powershell
.\scripts\run-all-benchmarks.ps1 `
  -DatabaseCpuLimit 4.0 `
  -DatabaseMemoryLimitBytes 8589934592
```

Milvus 보조 서비스 몫(0.5 vCPU + 512 MiB, 0.5 vCPU + 1 GiB)을 제외한 나머지가
자동으로 본체에 할당됩니다.

## 통제하지 못한 변수

아래는 현재 통제되지 않으며 결과 해석에 영향을 줍니다.

| 변수 | 현재 상태 |
|---|---|
| 클라이언트 직렬화 비용 | DB마다 다름 (JDBC 바이너리 vs JSON vs GraphQL 문자열) |
| 클라이언트 JVM 자원 | 상한 없음. DB 컨테이너와 같은 호스트 |
| Docker Desktop 네트워크 | Windows/WSL2 포트포워드 경유 |
| 동시에 떠 있는 PostgreSQL | 모든 프로필에서 4 vCPU / 8 GiB를 함께 점유 |
| 인덱스 빌드 반복 | 현재 1회. 빌드 편차를 반영하지 않음 |
| OpenSearch JVM heap | `-Xms4g -Xmx4g` 선점. 메모리 수치가 사용량이 아님 |

각 항목의 영향은 [limitations.md](limitations.md)에 있습니다.

## 관련 문서

- [experiment-design.md](experiment-design.md)
- [limitations.md](limitations.md)
