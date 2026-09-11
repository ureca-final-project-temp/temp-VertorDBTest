[CmdletBinding()]
param(
    [ValidateSet('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch')]
    [string[]]$Profiles = @('pgvector', 'qdrant', 'weaviate', 'milvus', 'opensearch'),
    [ValidateRange(3, 5)]
    [int]$Repetitions = 3,
    [string[]]$TestIds = @(),
    [string]$SourceDocumentVectors = 'data/embeddings/document-vectors.jsonl',
    [string]$SourceQueryDefinitions = 'data/queries/queries.jsonl',
    [string]$QueryVectors = 'data/embeddings/query-vectors.jsonl',
    [string]$WorkloadDirectory = 'data/workloads/filter-selectivity',
    [string]$ResultDirectory = 'benchmark-result/filter-selectivity'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $projectRoot

if (-not (Test-Path -LiteralPath (Join-Path $WorkloadDirectory 'manifest.json'))) {
    .\gradlew.bat generateFilterSelectivityWorkloads `
        "-PselectivityDocumentInput=$SourceDocumentVectors" `
        "-PselectivityQueryInput=$SourceQueryDefinitions" `
        "-PselectivityOutputDirectory=$WorkloadDirectory"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to generate filter-selectivity workloads.' }
}

foreach ($level in @('01', '10', '50')) {
    $arguments = @{
        Profiles = $Profiles
        Repetitions = $Repetitions
        DocumentVectors = Join-Path $WorkloadDirectory 'document-vectors.jsonl'
        QueryVectors = $QueryVectors
        QueryDefinitions = Join-Path $WorkloadDirectory "queries-$level.jsonl"
        ResultDirectory = Join-Path $ResultDirectory "selectivity-$level"
    }
    if ($TestIds.Count -gt 0) { $arguments.TestIds = $TestIds }
    & .\scripts\run-all-benchmarks.ps1 @arguments
    if ($LASTEXITCODE -ne 0) { throw "Filter-selectivity benchmark failed for $level%" }
}
