[CmdletBinding()]
param(
    [ValidateSet('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch')]
    [string[]]$Profiles = @('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch'),
    [ValidateRange(3, 5)]
    [int]$Repetitions = 3,
    [string[]]$TestIds = @(),
    [string]$ResultDirectory = 'benchmark-result/matrix-primary',
    [string]$MatrixFile = 'data/benchmark-matrix.json',
    [string]$DocumentVectors = 'data/embeddings/document-vectors.jsonl',
    [string]$QueryVectors = 'data/embeddings/query-vectors.jsonl',
    [string]$QueryDefinitions = 'data/queries/queries.jsonl',
    [ValidateRange(1, 1000000)]
    [int]$CalibrationQueryCount = 100,
    [ValidateRange(0, 1000000000)]
    [long]$MinimumVectorCount = 0,
    [switch]$RequireNonSynthetic,
    [ValidateRange(1.1, 128.0)]
    [double]$DatabaseCpuLimit = 4.0,
    [ValidateRange(1610612737, 1099511627776)]
    [long]$DatabaseMemoryLimitBytes = 8589934592,
    [ValidateRange(0.0, 1.0)]
    [double]$TargetRecallTolerance = 0.01,
    [ValidateRange(0.0, 1.0)]
    [double]$DriftThreshold = 0.05,
    [ValidateRange(0.0, 1.0)]
    [double]$DecisionRecallMinimum = 0.95,
    [ValidateRange(0.001, 600000)]
    [double]$DecisionP95LimitMs = 30.0,
    [ValidateRange(1048576, 1099511627776)]
    [long]$DecisionRamLimitBytes = 2147483648,
    [int]$ApplicationPort = 18086
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

function Resolve-ProjectPath {
    param([string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path $projectRoot $Path
}

function Count-Lines {
    param([string]$Path)
    $reader = [IO.File]::OpenText($Path)
    try {
        [long]$count = 0
        while ($null -ne $reader.ReadLine()) { $count++ }
        return $count
    } finally {
        $reader.Dispose()
    }
}

function Wait-HttpReady {
    param(
        [string]$Uri,
        [int]$TimeoutSeconds = 180,
        [System.Diagnostics.Process]$Process
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($null -ne $Process -and $Process.HasExited) {
            throw "Process exited with code $($Process.ExitCode) while waiting for $Uri"
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -TimeoutSec 5 -UseBasicParsing
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Milliseconds 750
        }
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Uri"
}

function Get-ServiceDescriptor {
    param([object]$CaseGroup)
    if ($CaseGroup.database -eq 'opensearch' -and $CaseGroup.engine -eq 'JVector') {
        return [pscustomobject]@{
            ComposeProfile = 'opensearch-jvector'
            Service = 'opensearch-jvector'
            Containers = @('vector-opensearch-jvector')
            ReadyUrl = 'http://localhost:19200/_cluster/health?wait_for_status=yellow&timeout=120s'
        }
    }
    switch ($CaseGroup.database) {
        'pgvector' {
            return [pscustomobject]@{ ComposeProfile = ''; Service = 'postgres'; Containers = @('vector-postgres'); ReadyUrl = '' }
        }
        'qdrant' {
            return [pscustomobject]@{ ComposeProfile = 'qdrant'; Service = 'qdrant'; Containers = @('vector-qdrant'); ReadyUrl = 'http://localhost:6333/healthz' }
        }
        'weaviate' {
            return [pscustomobject]@{ ComposeProfile = 'weaviate'; Service = 'weaviate'; Containers = @('vector-weaviate'); ReadyUrl = 'http://localhost:18080/v1/.well-known/ready' }
        }
        'milvus' {
            return [pscustomobject]@{ ComposeProfile = 'milvus'; Service = 'milvus'; Containers = @('vector-milvus', 'vector-milvus-etcd', 'vector-milvus-minio'); ReadyUrl = 'http://localhost:9091/healthz' }
        }
        'opensearch' {
            return [pscustomobject]@{ ComposeProfile = 'opensearch'; Service = 'opensearch'; Containers = @('vector-opensearch'); ReadyUrl = 'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=120s' }
        }
    }
    throw "Unknown database: $($CaseGroup.database)"
}

function Start-Database {
    param([object]$Descriptor)
    if ($Descriptor.Service -eq 'postgres') {
        docker compose up -d postgres --wait
    } elseif ($Descriptor.Service -eq 'milvus') {
        docker compose --profile $Descriptor.ComposeProfile up -d postgres milvus --wait
    } else {
        docker compose --profile $Descriptor.ComposeProfile up -d postgres $Descriptor.Service
    }
    if ($LASTEXITCODE -ne 0) { throw "Cannot start Docker service $($Descriptor.Service)" }
    if ($Descriptor.ReadyUrl) {
        $timeout = if ($Descriptor.Service -like 'opensearch*') { 360 } else { 240 }
        Wait-HttpReady $Descriptor.ReadyUrl $timeout
    }
}

function Assert-DatabaseResourceBudget {
    param([object]$Descriptor)
    $inspectJson = docker inspect $Descriptor.Containers
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect resource limits for $($Descriptor.Service)" }
    $inspected = @(($inspectJson | Out-String) | ConvertFrom-Json)
    $actualNanoCpus = [long](($inspected | ForEach-Object { [long]$_.HostConfig.NanoCpus } | Measure-Object -Sum).Sum)
    $actualMemoryBytes = [long](($inspected | ForEach-Object { [long]$_.HostConfig.Memory } | Measure-Object -Sum).Sum)
    $actualMemorySwapBytes = [long](($inspected | ForEach-Object { [long]$_.HostConfig.MemorySwap } | Measure-Object -Sum).Sum)
    $expectedNanoCpus = [long][Math]::Round($DatabaseCpuLimit * 1000000000)
    if ($actualNanoCpus -ne $expectedNanoCpus -or
            $actualMemoryBytes -ne $DatabaseMemoryLimitBytes -or
            $actualMemorySwapBytes -ne $DatabaseMemoryLimitBytes) {
        throw "Resource budget mismatch for $($Descriptor.Service): expected cpuNano=$expectedNanoCpus,memoryBytes=$DatabaseMemoryLimitBytes,memorySwapBytes=$DatabaseMemoryLimitBytes; actual cpuNano=$actualNanoCpus,memoryBytes=$actualMemoryBytes,memorySwapBytes=$actualMemorySwapBytes"
    }
    Write-Host "Resource budget verified: $($Descriptor.Service) = $DatabaseCpuLimit vCPU / $DatabaseMemoryLimitBytes bytes / no swap"
}

function Stop-Database {
    param([object]$Descriptor)
    if ($Descriptor.Service -eq 'postgres') { return }
    if ($Descriptor.Service -eq 'milvus') {
        docker compose --profile milvus stop milvus milvus-etcd milvus-minio
    } else {
        docker compose --profile $Descriptor.ComposeProfile stop $Descriptor.Service
    }
}

function Get-TuningCandidates {
    param([object]$CaseGroup)
    if ($CaseGroup.indexType -in @('IVFFlat', 'IVF_FLAT', 'IVF_SQ8', 'IVF_PQ', 'IVF')) {
        if ($CaseGroup.database -eq 'pgvector') { return '1,2,3,4,5,6,8,10' }
        return '1,2,4,8,16,32,64,96,128'
    }
    if ($CaseGroup.indexType -eq 'HFresh') { return '8,16,32,64,128,256,512,1024' }
    return '10,20,40,80,120,200,400,800,1000'
}

function Get-AdapterArguments {
    param([object]$CaseGroup, [object]$Descriptor)
    $arguments = @("--vector.$($CaseGroup.database).dimension=1024")
    switch ($CaseGroup.database) {
        'pgvector' {
            $indexType = if ($CaseGroup.indexType -eq 'IVFFlat') { 'ivfflat' } else { 'hnsw' }
            $arguments += "--vector.pgvector.index-type=$indexType"
        }
        'weaviate' {
            $arguments += "--vector.weaviate.index-type=$($CaseGroup.indexType.ToLowerInvariant())"
        }
        'milvus' {
            $arguments += "--vector.milvus.index-type=$($CaseGroup.indexType)"
        }
        'opensearch' {
            $indexType = switch ($CaseGroup.indexType) { 'DiskANN' { 'disk_ann' } default { $CaseGroup.indexType.ToLowerInvariant() } }
            $arguments += "--vector.opensearch.engine=$($CaseGroup.engine.ToLowerInvariant())"
            $arguments += "--vector.opensearch.index-type=$indexType"
            if ($CaseGroup.engine -eq 'JVector') {
                $arguments += '--vector.opensearch.base-url=http://localhost:19200'
                $arguments += '--benchmark.container-names=vector-opensearch-jvector'
            }
        }
    }
    $arguments += "--benchmark.auto-tune-candidates=$(Get-TuningCandidates $CaseGroup)"
    return $arguments
}

function Get-Median {
    param([double[]]$Values)
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return [double]::NaN }
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$middle] }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

function Write-Summary {
    param([string]$CsvPath)
    $rows = @(Import-Csv -LiteralPath $CsvPath)
    $summary = foreach ($group in ($rows | Group-Object test_id | Sort-Object Name)) {
        $samples = @($group.Group)
        $recalls = @($samples | ForEach-Object { [double]::Parse($_.comparison_recall, [Globalization.CultureInfo]::InvariantCulture) })
        $p95 = @($samples | ForEach-Object { [double]::Parse($_.unfiltered_p95_ms, [Globalization.CultureInfo]::InvariantCulture) })
        $qps = @($samples | ForEach-Object { [double]::Parse($_.qps, [Globalization.CultureInfo]::InvariantCulture) })
        $cpuAverage = @($samples | ForEach-Object { [double]::Parse($_.cpu_average_percent, [Globalization.CultureInfo]::InvariantCulture) })
        $cpuMax = @($samples | ForEach-Object { [double]::Parse($_.cpu_max_percent, [Globalization.CultureInfo]::InvariantCulture) })
        $ramAverage = @($samples | ForEach-Object { [double]::Parse($_.ram_average_bytes, [Globalization.CultureInfo]::InvariantCulture) })
        $ramMax = @($samples | ForEach-Object { [double]::Parse($_.ram_max_bytes, [Globalization.CultureInfo]::InvariantCulture) })
        $indexSize = @($samples | ForEach-Object { [double]::Parse($_.index_size_bytes, [Globalization.CultureInfo]::InvariantCulture) })
        $buildTime = @($samples | ForEach-Object { [double]::Parse($_.time_to_index_ready_ms, [Globalization.CultureInfo]::InvariantCulture) })
        $target = [double]::Parse($samples[0].target_recall, [Globalization.CultureInfo]::InvariantCulture)
        $recallAverage = ($recalls | Measure-Object -Average).Average
        $recallMinimum = ($recalls | Measure-Object -Minimum).Minimum
        $recallMaximum = ($recalls | Measure-Object -Maximum).Maximum
        $rebuildRecallRange = $recallMaximum - $recallMinimum
        $medianP95 = Get-Median $p95
        $medianRamMax = Get-Median $ramMax
        $complete = $samples.Count -eq $Repetitions
        $withinTargetTolerance = [Math]::Abs($recallAverage - $target) -le $TargetRecallTolerance
        $passesRecallFloor = $recallAverage -ge $DecisionRecallMinimum
        $passesP95 = $medianP95 -le $DecisionP95LimitMs
        $passesRam = $medianRamMax -le $DecisionRamLimitBytes
        $perRunStabilityVerified = @($samples | Where-Object { -not [bool]::Parse($_.stability_verified) }).Count -eq 0
        $stabilityRequired = @($samples | Where-Object { (($_.stability_diagnostics | ConvertFrom-Json).required) }).Count -gt 0
        $rebuildStabilityVerified = (-not $stabilityRequired) -or ($rebuildRecallRange -le $DriftThreshold)
        $stabilityVerified = $perRunStabilityVerified -and $rebuildStabilityVerified
        [pscustomobject]@{
            test_id = $group.Name
            database = $samples[0].database
            engine = $samples[0].engine
            index = $samples[0].index
            target_recall = $target
            completed_runs = $samples.Count
            recall_average = $recallAverage
            recall_min = $recallMinimum
            recall_max = $recallMaximum
            rebuild_recall_range = $rebuildRecallRange
            median_p95_ms = $medianP95
            median_qps = Get-Median $qps
            median_cpu_average_percent = Get-Median $cpuAverage
            max_cpu_percent = ($cpuMax | Measure-Object -Maximum).Maximum
            median_ram_average_bytes = Get-Median $ramAverage
            median_ram_max_bytes = $medianRamMax
            median_index_size_bytes = Get-Median $indexSize
            median_index_build_time_ms = Get-Median $buildTime
            within_target_tolerance = $withinTargetTolerance
            passes_recall_floor = $passesRecallFloor
            passes_p95 = $passesP95
            passes_ram = $passesRam
            per_run_stability_verified = $perRunStabilityVerified
            rebuild_stability_verified = $rebuildStabilityVerified
            stability_verified = $stabilityVerified
            eligible = $complete -and $passesRecallFloor -and $passesP95 -and $passesRam -and $stabilityVerified
        }
    }
    $summaryDirectory = Join-Path $resolvedResultDirectory 'summary'
    New-Item -ItemType Directory -Path $summaryDirectory -Force | Out-Null
    $summary | Export-Csv -LiteralPath (Join-Path $summaryDirectory 'vector-db-summary.csv') -NoTypeInformation -Encoding utf8
}

# Milvus includes its two supporting services within the same total budget.
$milvusEtcdCpu = 0.5
$milvusMinioCpu = 0.5
$milvusEtcdMemoryBytes = 512MB
$milvusMinioMemoryBytes = 1GB
$milvusCpuLimit = $DatabaseCpuLimit - $milvusEtcdCpu - $milvusMinioCpu
$milvusMemoryLimitBytes = $DatabaseMemoryLimitBytes - $milvusEtcdMemoryBytes - $milvusMinioMemoryBytes
if ($milvusCpuLimit -le 0 -or $milvusMemoryLimitBytes -le 0) {
    throw 'The database budget is too small for the Milvus supporting services.'
}

$invariantCulture = [Globalization.CultureInfo]::InvariantCulture
$env:VECTOR_CPU_LIMIT = $DatabaseCpuLimit.ToString($invariantCulture)
$env:VECTOR_MEMORY_LIMIT = $DatabaseMemoryLimitBytes.ToString($invariantCulture)
$env:MILVUS_CPU_LIMIT = $milvusCpuLimit.ToString($invariantCulture)
$env:MILVUS_MEMORY_LIMIT = $milvusMemoryLimitBytes.ToString($invariantCulture)
$env:MILVUS_ETCD_CPU_LIMIT = $milvusEtcdCpu.ToString($invariantCulture)
$env:MILVUS_ETCD_MEMORY_LIMIT = $milvusEtcdMemoryBytes.ToString($invariantCulture)
$env:MILVUS_MINIO_CPU_LIMIT = $milvusMinioCpu.ToString($invariantCulture)
$env:MILVUS_MINIO_MEMORY_LIMIT = $milvusMinioMemoryBytes.ToString($invariantCulture)

$jar = Join-Path $projectRoot 'build/libs/VectorDBTest-0.0.1-SNAPSHOT.jar'
$matrixPath = Resolve-ProjectPath $MatrixFile
$documentVectorsPath = Resolve-ProjectPath $DocumentVectors
$queryVectorsPath = Resolve-ProjectPath $QueryVectors
$queryDefinitionsPath = Resolve-ProjectPath $QueryDefinitions
$resolvedResultDirectory = Resolve-ProjectPath $ResultDirectory
$logDirectory = Join-Path $resolvedResultDirectory 'logs'

foreach ($required in @($jar, $matrixPath, $documentVectorsPath, $queryVectorsPath, $queryDefinitionsPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required file does not exist: $required" }
}
$vectorCount = Count-Lines $documentVectorsPath
if ($vectorCount -lt $MinimumVectorCount) {
    throw "Dataset has $vectorCount vectors, below MinimumVectorCount=$MinimumVectorCount"
}
if ((Count-Lines $queryVectorsPath) -le $CalibrationQueryCount) {
    throw 'Query vector count must be greater than CalibrationQueryCount.'
}
if ($RequireNonSynthetic) {
    $syntheticQueries = Select-String -LiteralPath $queryDefinitionsPath -Pattern '"synthetic"\s*:\s*true'
    if ($syntheticQueries) { throw 'RequireNonSynthetic was set, but query definitions contain synthetic=true.' }
    $syntheticDocuments = Select-String -LiteralPath $documentVectorsPath -Pattern '"synthetic"\s*:\s*true'
    if ($syntheticDocuments) { throw 'RequireNonSynthetic was set, but document metadata contains synthetic=true.' }
}

$matrix = @((Get-Content -LiteralPath $matrixPath -Raw | ConvertFrom-Json) |
        Where-Object { $_.database -in $Profiles -and ($TestIds.Count -eq 0 -or $_.testId -in $TestIds) })
if ($matrix.Count -eq 0) { throw 'No matrix rows match Profiles/TestIds.' }
$unknownIds = @($TestIds | Where-Object { $_ -notin $matrix.testId })
if ($unknownIds.Count -gt 0) { throw "Unknown or excluded TestIds: $($unknownIds -join ', ')" }

New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$orders = @(
    @('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch'),
    @('weaviate', 'opensearch', 'pgvector', 'qdrant', 'milvus'),
    @('milvus', 'qdrant', 'opensearch', 'weaviate', 'pgvector'),
    @('opensearch', 'milvus', 'weaviate', 'pgvector', 'qdrant'),
    @('qdrant', 'pgvector', 'milvus', 'opensearch', 'weaviate')
)
$runOrders = @()

try {
    for ($runNumber = 1; $runNumber -le $Repetitions; $runNumber++) {
        $databaseOrder = @($orders[$runNumber - 1] | Where-Object { $_ -in $Profiles })
        $runOrders += [pscustomobject]@{ runNumber = $runNumber; databaseOrder = $databaseOrder }
        foreach ($database in $databaseOrder) {
            $caseGroups = @($matrix | Where-Object database -eq $database | Group-Object engine, indexType)
            foreach ($group in $caseGroups) {
                $caseGroup = $group.Group[0]
                $descriptor = Get-ServiceDescriptor $caseGroup
                $label = "$($caseGroup.database)-$($caseGroup.engine)-$($caseGroup.indexType)".ToLowerInvariant() -replace '[^a-z0-9-]', '-'
                Write-Host "=== run $runNumber/$Repetitions : $label ==="
                $application = $null
                try {
                    Start-Database $descriptor
                    Assert-DatabaseResourceBudget $descriptor
                    $stdout = Join-Path $logDirectory "run-$runNumber-$label-application.log"
                    $stderr = Join-Path $logDirectory "run-$runNumber-$label-application-error.log"
                    $arguments = @(
                        '-jar', $jar,
                        "--spring.profiles.active=$($caseGroup.database)",
                        "--server.port=$ApplicationPort",
                        "--benchmark.document-vectors=$documentVectorsPath",
                        "--benchmark.query-definitions=$queryDefinitionsPath",
                        "--benchmark.query-vectors=$queryVectorsPath",
                        "--benchmark.calibration-query-count=$CalibrationQueryCount",
                        "--benchmark.target-recall-tolerance=$TargetRecallTolerance",
                        "--benchmark.drift-threshold=$DriftThreshold",
                        "--benchmark.result-directory=$resolvedResultDirectory",
                        "--benchmark.resource-budget-cpu=$DatabaseCpuLimit",
                        "--benchmark.resource-budget-memory-bytes=$DatabaseMemoryLimitBytes"
                    )
                    $arguments += Get-AdapterArguments $caseGroup $descriptor
                    $application = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -WindowStyle Hidden `
                        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
                    Wait-HttpReady "http://localhost:$ApplicationPort/actuator/health" 180 $application

                    $scenarios = @($group.Group | Sort-Object testId | ForEach-Object {
                        [ordered]@{
                            testId = $_.testId
                            runNumber = $runNumber
                            database = $_.database
                            engine = $_.engine
                            indexType = $_.indexType
                            targetRecall = $_.targetRecall
                            topK = 10
                            concurrency = 10
                            warmupIterations = 1
                            measurementIterations = 5
                            searchParameters = @{}
                        }
                    })
                    $body = @{ rebuildAndLoad = $true; scenarios = $scenarios } | ConvertTo-Json -Depth 10
                    $response = Invoke-RestMethod -Method Post -Uri "http://localhost:$ApplicationPort/api/benchmarks/run" `
                        -ContentType 'application/json' -Body $body -TimeoutSec 7200
                    $response | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath `
                        (Join-Path $resolvedResultDirectory "api-response-run-$runNumber-$label.json") -Encoding utf8
                    $response.results | Select-Object testId, runNumber, database, engine, indexType,
                        targetRecall, comparisonRecall, targetMet, calibrationSelection, calibrationRecall,
                        @{ Name = 'p95Ms'; Expression = { $_.unfiltered.p95Ms } }, qps,
                        averageCpuPercent, peakCpuPercent, averageMemoryBytes, peakMemoryBytes,
                        indexSizeBytes, indexBuildTimeMs,
                        @{ Name = 'stabilityVerified'; Expression = { $_.stabilityDiagnostics.verified } },
                        searchParameters | Format-Table -AutoSize
                } finally {
                    if ($application -and -not $application.HasExited) {
                        Stop-Process -Id $application.Id
                        $application.WaitForExit(10000) | Out-Null
                    }
                    Stop-Database $descriptor
                }
            }
        }
    }
} finally {
    docker compose --profile qdrant --profile weaviate --profile milvus --profile opensearch --profile opensearch-jvector stop `
        qdrant weaviate milvus milvus-etcd milvus-minio opensearch opensearch-jvector postgres
}

$runOrders | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $resolvedResultDirectory 'execution-order.json') -Encoding utf8
$csvPath = Join-Path $resolvedResultDirectory 'csv/vector-db-result.csv'
Write-Summary $csvPath
Write-Host "Completed. Raw results: $csvPath"
Write-Host "Aggregated results: $(Join-Path $resolvedResultDirectory 'summary/vector-db-summary.csv')"
