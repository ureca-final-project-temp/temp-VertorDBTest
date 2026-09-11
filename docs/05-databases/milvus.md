# Milvus

Native engine에서 HNSW, IVF_FLAT, IVF_SQ8, IVF_PQ, DISKANN(T11~T20)을 측정합니다. standalone 본체·etcd·MinIO 합계에 4 vCPU/8GiB 제한을 적용합니다.

## 인덱스와 검색 파라미터

| Index | Build | Search |
|---|---|---|
| HNSW | M=16, efConstruction=128 | ef |
| IVF_FLAT | nlist=128 | nprobe |
| IVF_SQ8 | nlist=128 | nprobe |
| IVF_PQ | nlist=128, m=64, nbits=8 | nprobe |
| DISKANN | server default build params | search_list |

IVF_PQ는 dimension이 `m`으로 나누어떨어지지 않으면 실행을 거부합니다. DISKANN을 위해 `docker/milvus/user.yaml`의 `queryNode.enableDisk: true`를 container config overlay로 mount합니다.

## 준비 장벽

insert 뒤 `collections/flush`와 비동기 `collections/load`를 호출합니다. `indexes/describe`의 `indexState=Finished`, indexed rows 전체 이상, pending rows 0과 `get_load_state`의 `LoadStateLoaded`, progress 100%만으로는 부족합니다. 공식 SDK `getQuerySegmentInfo`에도 기대 row 전체와 index name을 가진 Sealed/Flushed segment가 나타날 때까지 기다립니다. growing segment나 query-node load 전환 경로를 ANN 결과로 잘못 측정하지 않습니다.

## drift 진단

과거 단일 실행에서 calibration 0.9578 → 본 측정 0.7422가 관측됐으므로 Milvus는 자동 stability audit 대상입니다. 각 결과 행에서 선택된 같은 파라미터와 calibration 무필터 query를 사용해 serial 3회와 concurrency 10의 3회를 추가 실행합니다.

원인은 REST index/load 상태가 완료여도 query node의 segment 목록이 아직 비어 있던 readiness 간극이었습니다. SDK segment barrier를 추가한 뒤 HNSW T12와 IVF 계열 재구축 3회에서 같은 형태의 drift는 재현되지 않았습니다. 상세 증거는 [adapter smoke](../07-results/adapter-smoke-20260911.md)에 있습니다.

공식 Java SDK의 `getQuerySegmentInfo`로 segment ID/state/rows/memory/index/node 정보를 받고 REST로 index state와 load state를 진단 전후 저장합니다. calibration 대비 concurrent 최대 편차나 serial/concurrent 중앙값 차이가 기본 0.05를 넘거나 상태 수집이 실패하면 실패입니다. 반복 집계에서도 재구축 간 evaluation Recall max-min이 0.05를 넘으면 `stability_verified=false`이며 최종 eligible에서 제외됩니다.

진단 검색은 최종 QPS/latency timer 밖입니다.

## 필터와 크기

metadata는 JSON field 표현식으로 필터합니다. JSON path index는 주 matrix에 포함하지 않았습니다. 순수 per-index size를 REST 결과에서 분리하지 않으므로 `index_size_bytes=-1`입니다.

## 참고

- [Milvus disk index](https://milvus.io/docs/disk_index.md)
- [Milvus query node disk 설정](https://milvus.io/docs/configure_querynode.md)
- [Java SDK getQuerySegmentInfo](https://milvus.io/api-reference/java/v3.0.x/v2/Management/getQuerySegmentInfo.md)
