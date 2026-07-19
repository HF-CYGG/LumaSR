[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$validatorPath = Join-Path $PSScriptRoot 'validate-release-metadata.ps1'

if (!(Test-Path -LiteralPath $validatorPath -PathType Leaf)) {
    throw "Release metadata validator is missing: $validatorPath"
}

& $validatorPath -TagName 'v0.3.2' -ProjectRoot $projectRoot

foreach ($invalidTag in @('release', 'v0.3.2-beta', 'v0.3.1')) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File $validatorPath `
        -TagName $invalidTag `
        -ProjectRoot $projectRoot 2>&1
    $validatorExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($validatorExitCode -eq 0) {
        throw "Release metadata validator unexpectedly accepted: $invalidTag"
    }
}

Write-Host 'Release metadata validation tests passed.'
