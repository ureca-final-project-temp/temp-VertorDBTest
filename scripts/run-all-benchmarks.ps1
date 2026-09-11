[CmdletBinding()]
param(
    [ValidateSet('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch')]
    [string[]]$Profiles = @('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch'),
    [string]$ResultDirectory = 'benchmark-result/production',
    [int]$ApplicationPort = 18086
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'build/libs/VectorDBTest-0.0.1-SNAPSHOT.jar'
$requestPath = Join-Path $projectRoot 'data/benchmark-request.json'
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
    param([string]$Uri, [int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
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
                "--benchmark.result-directory=$resolvedResultDirectory"
            )
            $application = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -WindowStyle Hidden `
                -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            Wait-HttpReady "http://localhost:$ApplicationPort/actuator/health" 120

            $body = Get-Content -LiteralPath $requestPath -Raw
            $response = Invoke-RestMethod -Method Post -Uri "http://localhost:$ApplicationPort/api/benchmarks/run" `
                -ContentType 'application/json' -Body $body -TimeoutSec 3600
            $response | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath `
                (Join-Path $resolvedResultDirectory "api-response-$profile.json") -Encoding utf8
            $response.results | Select-Object database, targetRecall, actualRecall, p95LatencyMs, qps,
                averageCpuPercent, peakMemoryBytes, indexSizeBytes, searchParameters | Format-Table -AutoSize
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
