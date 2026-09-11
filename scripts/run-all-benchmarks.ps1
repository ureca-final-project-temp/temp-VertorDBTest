[CmdletBinding()]
param(
    [ValidateSet('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch')]
    [string[]]$Profiles = @('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch'),
    [string]$ResultDirectory = 'benchmark-result/primary-recall',
    [string]$RequestFile = 'data/benchmark-request.json',
    [ValidateRange(1.1, 128.0)]
    [double]$DatabaseCpuLimit = 4.0,
    [ValidateRange(1610612737, 1099511627776)]
    [long]$DatabaseMemoryLimitBytes = 8589934592,
    [int]$ApplicationPort = 18086
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

# Milvus needs two supporting services. Their limits are part of, not in
# addition to, the database deployment budget.
$milvusEtcdCpu = 0.5
$milvusMinioCpu = 0.5
$milvusEtcdMemoryBytes = 512MB
$milvusMinioMemoryBytes = 1GB
$milvusCpuLimit = $DatabaseCpuLimit - $milvusEtcdCpu - $milvusMinioCpu
$milvusMemoryLimitBytes = $DatabaseMemoryLimitBytes - $milvusEtcdMemoryBytes - $milvusMinioMemoryBytes
if ($milvusCpuLimit -le 0 -or $milvusMemoryLimitBytes -le 0) {
    throw 'The database budget is too small for the Milvus supporting services.'
}

$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture
$env:VECTOR_CPU_LIMIT = $DatabaseCpuLimit.ToString($invariantCulture)
$env:VECTOR_MEMORY_LIMIT = $DatabaseMemoryLimitBytes.ToString($invariantCulture)
$env:MILVUS_CPU_LIMIT = $milvusCpuLimit.ToString($invariantCulture)
$env:MILVUS_MEMORY_LIMIT = $milvusMemoryLimitBytes.ToString($invariantCulture)
$env:MILVUS_ETCD_CPU_LIMIT = $milvusEtcdCpu.ToString($invariantCulture)
$env:MILVUS_ETCD_MEMORY_LIMIT = $milvusEtcdMemoryBytes.ToString($invariantCulture)
$env:MILVUS_MINIO_CPU_LIMIT = $milvusMinioCpu.ToString($invariantCulture)
$env:MILVUS_MINIO_MEMORY_LIMIT = $milvusMinioMemoryBytes.ToString($invariantCulture)

$jar = Join-Path $projectRoot 'build/libs/VectorDBTest-0.0.1-SNAPSHOT.jar'
$requestPath = if ([IO.Path]::IsPathRooted($RequestFile)) { $RequestFile } else { Join-Path $projectRoot $RequestFile }
$documentVectors = Join-Path $projectRoot 'data/embeddings/document-vectors.jsonl'
$queryVectors = Join-Path $projectRoot 'data/embeddings/query-vectors.jsonl'
$queryDefinitions = Join-Path $projectRoot 'data/queries/queries.jsonl'
$resolvedResultDirectory = Join-Path $projectRoot $ResultDirectory
$logDirectory = Join-Path $resolvedResultDirectory 'logs'

foreach ($required in @($jar, $requestPath, $documentVectors, $queryVectors, $queryDefinitions)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file does not exist: $required"
    }
}
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

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

function Start-Database {
    param([string]$Profile)
    switch ($Profile) {
        'pgvector' {
            docker compose up -d postgres --wait
        }
        'qdrant' {
            docker compose --profile qdrant up -d postgres qdrant
            Wait-HttpReady 'http://localhost:6333/healthz'
        }
        'weaviate' {
            docker compose --profile weaviate up -d postgres weaviate
            Wait-HttpReady 'http://localhost:18080/v1/.well-known/ready'
        }
        'milvus' {
            docker compose --profile milvus up -d postgres milvus --wait
            Wait-HttpReady 'http://localhost:9091/healthz'
        }
        'opensearch' {
            docker compose --profile opensearch up -d postgres opensearch
            Wait-HttpReady 'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=120s' 240
        }
    }
    if ($LASTEXITCODE -ne 0) { throw "Cannot start Docker services for $Profile" }
}

function Assert-DatabaseResourceBudget {
    param([string]$Profile)
    $containers = switch ($Profile) {
        'pgvector' { @('vector-postgres') }
        'qdrant' { @('vector-qdrant') }
        'weaviate' { @('vector-weaviate') }
        'milvus' { @('vector-milvus', 'vector-milvus-etcd', 'vector-milvus-minio') }
        'opensearch' { @('vector-opensearch') }
    }

    $inspectJson = docker inspect $containers
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect resource limits for $Profile" }
    $inspected = @(($inspectJson | Out-String) | ConvertFrom-Json)
    $actualNanoCpus = [long](($inspected | ForEach-Object { [long]$_.HostConfig.NanoCpus } | Measure-Object -Sum).Sum)
    $actualMemoryBytes = [long](($inspected | ForEach-Object { [long]$_.HostConfig.Memory } | Measure-Object -Sum).Sum)
    $actualMemorySwapBytes = [long](($inspected | ForEach-Object { [long]$_.HostConfig.MemorySwap } | Measure-Object -Sum).Sum)
    $expectedNanoCpus = [long][Math]::Round($DatabaseCpuLimit * 1000000000)

    if ($actualNanoCpus -ne $expectedNanoCpus -or
            $actualMemoryBytes -ne $DatabaseMemoryLimitBytes -or
            $actualMemorySwapBytes -ne $DatabaseMemoryLimitBytes) {
        throw "Resource budget mismatch for $Profile`: expected cpuNano=$expectedNanoCpus,memoryBytes=$DatabaseMemoryLimitBytes,memorySwapBytes=$DatabaseMemoryLimitBytes; actual cpuNano=$actualNanoCpus,memoryBytes=$actualMemoryBytes,memorySwapBytes=$actualMemorySwapBytes"
    }
    Write-Host "Resource budget verified: $Profile = $DatabaseCpuLimit vCPU / $DatabaseMemoryLimitBytes bytes / no swap"
}

function Stop-Database {
    param([string]$Profile)
    switch ($Profile) {
        'qdrant' { docker compose --profile qdrant stop qdrant }
        'weaviate' { docker compose --profile weaviate stop weaviate }
        'milvus' { docker compose --profile milvus stop milvus milvus-etcd milvus-minio }
        'opensearch' { docker compose --profile opensearch stop opensearch }
    }
}

try {
    foreach ($profile in $Profiles) {
        Write-Host "=== $profile benchmark ==="
        $application = $null
        try {
            Start-Database $profile
            Assert-DatabaseResourceBudget $profile
            $stdout = Join-Path $logDirectory "$profile-application.log"
            $stderr = Join-Path $logDirectory "$profile-application-error.log"
            $arguments = @(
                '-jar', $jar,
                "--spring.profiles.active=$profile",
                "--server.port=$ApplicationPort",
                "--vector.$profile.dimension=1024",
                "--benchmark.document-vectors=$documentVectors",
                "--benchmark.query-definitions=$queryDefinitions",
                "--benchmark.query-vectors=$queryVectors",
                "--benchmark.result-directory=$resolvedResultDirectory",
                "--benchmark.resource-budget-cpu=$DatabaseCpuLimit",
                "--benchmark.resource-budget-memory-bytes=$DatabaseMemoryLimitBytes"
            )
            $application = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -WindowStyle Hidden `
                -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            Wait-HttpReady "http://localhost:$ApplicationPort/actuator/health" 120 $application

            $body = Get-Content -LiteralPath $requestPath -Raw
            $response = Invoke-RestMethod -Method Post -Uri "http://localhost:$ApplicationPort/api/benchmarks/run" `
                -ContentType 'application/json' -Body $body -TimeoutSec 3600
            $response | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath `
                (Join-Path $resolvedResultDirectory "api-response-$profile.json") -Encoding utf8
            # Filtered and unfiltered queries are shown apart: with a filtered minority the
            # combined p95 reports filter cost, not ANN tail latency.
            $response.results | Select-Object database, targetRecall, actualRecall, comparisonRecall, targetMet,
                recallSelection, tuningRecall,
                @{ Name = 'unfilteredP95Ms'; Expression = { $_.unfiltered.p95Ms } },
                @{ Name = 'unfilteredRecall'; Expression = { $_.unfiltered.recall } },
                @{ Name = 'filteredP95Ms'; Expression = { $_.filtered.p95Ms } },
                @{ Name = 'filteredRecall'; Expression = { $_.filtered.recall } },
                @{ Name = 'combinedP95Ms'; Expression = { $_.p95LatencyMs } },
                qps, averageCpuPercent, peakMemoryBytes, indexSizeBytes, searchParameters | Format-Table -AutoSize
        } finally {
            if ($application -and -not $application.HasExited) {
                Stop-Process -Id $application.Id
                $application.WaitForExit(10000) | Out-Null
            }
            Stop-Database $profile
        }
    }
} finally {
    docker compose stop postgres
}

Write-Host "Completed. Results: $resolvedResultDirectory"
