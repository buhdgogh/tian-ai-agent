param(
    [ValidateRange(1, 20)]
    [int]$TopK = 5
)

$ErrorActionPreference = 'Stop'
$token = ([string]$env:TIAN_EVAL_TOKEN).Trim()
$spaceId = ([string]$env:TIAN_EVAL_SPACE_ID).Trim()
if (-not $token -or -not $spaceId) {
    throw '请设置 TIAN_EVAL_TOKEN（管理员令牌）和 TIAN_EVAL_SPACE_ID。'
}

$base = ([string]$env:TIAN_EVAL_BASE_URL).Trim().TrimEnd('/')
if (-not $base) { $base = 'http://127.0.0.1:8123/api' }
$headers = @{ Authorization = "Bearer $token" }
$encodedSpace = [Uri]::EscapeDataString($spaceId)
$files = Invoke-RestMethod "$base/knowledge-base/files?spaceId=$encodedSpace" -Headers $headers
$tests = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tests/rag.json') -Raw -Encoding UTF8 | ConvertFrom-Json

$cases = foreach ($test in $tests) {
    $expectedIds = foreach ($name in ($test.vars.expectedEvidence.fileName | Select-Object -Unique)) {
        $matchingFiles = @($files | Where-Object { $_.name -ceq $name })
        if ($matchingFiles.Count -ne 1 -or -not $matchingFiles[0].id) {
            throw "评测空间须包含唯一一份 $name。请检查上传状态和文件名。"
        }
        $file = $matchingFiles[0]
        if ($file.status -notin @('INDEXED', 'INDEXED_DEGRADED')) {
            throw "评测文档 $name 尚未完成索引（状态：$($file.status)）。"
        }
        $file.id
    }
    @{
        id = $test.vars.caseId
        question = $test.vars.question
        expectedDocumentIds = @($expectedIds)
    }
}

$body = @{
    name = 'Tian eval retrieval baseline'
    spaceId = $spaceId
    topK = $TopK
    generateAnswers = $false
    cases = @($cases)
} | ConvertTo-Json -Depth 8

Invoke-RestMethod "$base/rag-evaluations" -Method Post -Headers $headers `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
