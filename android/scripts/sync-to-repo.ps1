param([string]$Repo = "C:\Users\Curioud\Documents\Codex\2026-10-10\task-5\web-eyecare")
# Mirrors this Android project into <repo>\android. Local tools, build outputs, APKs and
# personal measurement data stay out of the repository, and so do the internal planning
# documents (PRDs, product strategy, test reviews), because the repository is public.
$ErrorActionPreference = 'Stop'
$source = Split-Path $PSScriptRoot -Parent
$target = Join-Path $Repo 'android'
robocopy $source $target /MIR /NFL /NDL /NJH /NJS /NP `
    /XD (Join-Path $source '.tools') (Join-Path $source '.gradle') (Join-Path $source '.kotlin') `
        (Join-Path $source '.bkit') (Join-Path $source 'artifacts') (Join-Path $source 'reports') `
        (Join-Path $source 'app\build') (Join-Path $source 'build') .idea captures node_modules `
    /XF local.properties *.jks *.keystore EyeCare_*PRD*.md EyeCare_PRODUCT_STRATEGY*.md TEST_REVIEW_*.md | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed: $LASTEXITCODE" }
Write-Output "synced to $target"
