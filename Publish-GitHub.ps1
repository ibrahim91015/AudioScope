# Publish tagged builds in numeric version order; safe to resume after interruption.
# Compatible with Windows PowerShell 5.1 and PowerShell 7.
# Default: reconcile all local vMAJOR.MINOR.PATCH tags, oldest first.
# Optional: -Version 0.3.0 for one version, or -ValidateOnly for offline checks.
# Existing release descriptions and completed uploads are never overwritten.
[CmdletBinding()]
param(
    [string]$RepositoryName = 'AudioScope',
    [string[]]$Version = @(),
    [string]$Apk,
    [string]$SourceZip,
    [string]$RepositoryPath,
    [string]$ArtifactsDirectory,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepositoryPath)) { $RepositoryPath = $PSScriptRoot }
$RepositoryPath = [IO.Path]::GetFullPath($RepositoryPath)
if ([string]::IsNullOrWhiteSpace($ArtifactsDirectory)) { $ArtifactsDirectory = Split-Path $RepositoryPath -Parent }
$expectedAccount = 'ibrahim91015'
$headers = $null
$credential = $null
$credentialLines = $null
$originalPath = $env:Path
$originalEncoding = [Console]::OutputEncoding
$originalPipeEncoding = $OutputEncoding
$originalProtocol = [Net.ServicePointManager]::SecurityProtocol
$gitExecutable = (Get-Command git -ErrorAction Stop).Source
$gitHelperDirectory = 'C:\Program Files\Git\mingw64\bin'
if (Test-Path -LiteralPath $gitHelperDirectory) { $env:Path = $gitHelperDirectory + ';' + $env:Path.Replace('"', '') }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding

function Invoke-PublishGit {
    param([string[]]$Arguments)
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $lines = @(& $gitExecutable @Arguments 2>&1); $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $savedPreference }
    if ($code -ne 0) { throw ("Git failed: git {0}`n{1}" -f ($Arguments -join ' '), ($lines -join "`n")) }
    foreach ($line in $lines) { [string]$line }
}

function Get-PublishVersions {
    param([string[]]$Requested, [string[]]$Tags)
    $available = @($Tags | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | ForEach-Object { $_.Substring(1) })
    if ($Requested.Count) {
        foreach ($value in $Requested) {
            if ($value -notmatch '^\d+\.\d+\.\d+$') { throw "Invalid version: $value" }
            if ($available -notcontains $value) { throw "Local tag v$value is missing. Tag the matching built source first." }
        }
        $available = $Requested
    }
    @($available | Sort-Object -Unique | Sort-Object { [version]$_ })
}

function Convert-ReleaseJson {
    param([hashtable]$Payload)
    if ($Payload.ContainsKey('body')) {
        if ($Payload.body -isnot [string]) { throw 'Release notes must be plain text.' }
        # Copy to a fresh .NET string, discarding Get-Content's extended properties.
        $Payload.body = [string]::Concat('', $Payload.body)
    }
    $json = ConvertTo-Json -InputObject $Payload -Depth 8 -Compress
    if ($Payload.ContainsKey('body')) {
        $decoded = ConvertFrom-Json -InputObject $json
        if ($decoded.body -isnot [string] -or $decoded.body -cne $Payload.body) { throw 'Release notes failed JSON string validation. Nothing was sent.' }
    }
    ,([Text.Encoding]::UTF8.GetBytes($json))
}

function Get-PublishNotes {
    param([string]$Tag, [string]$Number)
    $path = "docs/RELEASE-$Number.md"
    $paths = @(Invoke-PublishGit @('ls-tree', '--name-only', $Tag, '--', $path))
    if ($paths -contains $path) {
        $lines = @(Invoke-PublishGit @('show', "${Tag}:$path"))
        return [string]::Join("`n", [string[]]$lines) + "`n"
    }
    "AudioScope $Number experimental Android build. See README and docs/VALIDATION.md in this tag."
}

function Get-HttpStatus {
    param($Failure)
    try { [int]$Failure.Exception.Response.StatusCode } catch { 0 }
}
function Test-TemporaryFailure {
    param($Failure)
    $status = Get-HttpStatus $Failure
    if ($status -in @(0,408,429,500,502,503,504)) { return $true }
    if ($status -eq 403) {
        try {
            if ($Failure.Exception.Response.Headers['Retry-After'] -or
                [string]$Failure.Exception.Response.Headers['X-RateLimit-Remaining'] -eq '0') { return $true }
        } catch { }
        if ([string]$Failure.ErrorDetails.Message -match 'rate limit|secondary rate') { return $true }
    }
    $false
}
function Wait-PublishRetry {
    param([int]$Attempt, $Failure)
    $seconds = [Math]::Min(30, [Math]::Pow(2, $Attempt))
    try {
        $suggested = 0
        if ([int]::TryParse([string]$Failure.Exception.Response.Headers['Retry-After'], [ref]$suggested)) {
            if ($suggested -gt 30) { throw "GitHub requested a $suggested-second pause. Rerun later; completed uploads are preserved." }
            $seconds = [Math]::Max($seconds, $suggested)
        }
    } catch { if ($_.Exception.Message -like 'GitHub requested*') { throw } }
    Write-Host "Temporary GitHub failure; checking existing state and retrying in $seconds seconds..."
    Start-Sleep -Seconds $seconds
}
function Invoke-PublishRequest {
    param([string]$Uri, [string]$Method='Get', [byte[]]$Json, [string]$File)
    $parameters = @{ Uri=$Uri; Method=$Method; Headers=$headers; TimeoutSec=180; ErrorAction='Stop' }
    if ($null -ne $Json) { $parameters.ContentType='application/json; charset=utf-8'; $parameters.Body=$Json }
    if ($File) { $parameters.ContentType='application/octet-stream'; $parameters.InFile=$File }
    Invoke-RestMethod @parameters
}
function Get-PublishResource {
    param([string]$Uri, [switch]$AllowMissing)
    for ($attempt=1; $attempt -le 4; $attempt++) {
        try { return Invoke-PublishRequest -Uri $Uri }
        catch {
            if ($AllowMissing -and (Get-HttpStatus $_) -eq 404) { return $null }
            if ($attempt -eq 4 -or !(Test-TemporaryFailure $_)) { throw }
            Wait-PublishRetry $attempt $_
        }
    }
}
function Get-PublishAssets {
    param($Release)
    for ($page=1; ; $page++) {
        $items = @(Get-PublishResource ($Release.assets_url + "?per_page=100&page=$page"))
        foreach ($item in $items) { $item }
        if ($items.Count -lt 100) { break }
    }
}
function Test-PublishAsset {
    param($Asset, [IO.FileInfo]$File)
    if ($Asset.state -ne 'uploaded') { return $false }
    if ([long]$Asset.size -ne $File.Length) { throw "Existing asset $($File.Name) differs in size. It was left untouched; inspect it on GitHub." }
    if ($Asset.digest) {
        $hash = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([string]$Asset.digest -ne "sha256:$hash") { throw "Existing asset $($File.Name) has a different checksum. It was left untouched." }
    }
    $true
}
function Assert-PublishFile {
    param([string]$Path, [switch]$CheckApk)
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Build artifact missing: $Path. Supply that version's signed build, then rerun." }
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -eq 0) { throw "Build artifact is empty: $Path" }
    if ($CheckApk) {
        $checksum = $Path + '.sha256'
        if (!(Test-Path -LiteralPath $checksum)) { $checksum = [IO.Path]::ChangeExtension($Path, '.sha256') }
        if (Test-Path -LiteralPath $checksum) {
            $text = [IO.File]::ReadAllText($checksum, [Text.Encoding]::UTF8).Trim()
            if ($text -notmatch '^(?<hash>[a-fA-F0-9]{64})(?:\s|$)') { throw "Invalid checksum file: $checksum" }
            $expected = $Matches.hash.ToLowerInvariant()
            if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw "APK checksum mismatch: $Path. Nothing from this version was published." }
        }
    }
    $file
}
function Get-PublishCredential {
    param([string]$Executable, [string]$Account)
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @("protocol=https`nhost=github.com`nusername=$Account`n`n" | & $Executable credential fill 2>$null)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($code -ne 0) { throw 'Git credential lookup failed. Run this script in your normal PowerShell terminal, where GitHub sign-in works.' }
    $result = @{}
    foreach ($line in $lines) { $parts = [string]$line -split '=',2; if ($parts.Count -eq 2) { $result[$parts[0]]=$parts[1] } }
    $lines=$null
    if (!$result.password) { throw 'Git returned no GitHub credential. Sign in using Git Credential Manager in your normal terminal.' }
    $result
}
function Ensure-PublishSourceZip {
    param([string]$Tag, [string]$Number, [string]$Path, [switch]$PlanOnly)
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return }
    if ($PlanOnly) { Write-Host "Source ZIP will be generated from ${Tag}: $Path"; return }
    $destination = [IO.Path]::GetFullPath($Path)
    $directory = [IO.Path]::GetDirectoryName($destination)
    if (!(Test-Path -LiteralPath $directory -PathType Container)) { throw "Artifact directory does not exist: $directory" }
    $temporary = Join-Path $directory ('.audioscope-archive-' + [Guid]::NewGuid().ToString('N') + '.zip')
    try {
        # Export the immutable tag, never the current checkout or uncommitted work.
        Invoke-PublishGit @('archive','--format=zip',"--prefix=AudioScope-$Number/","--output=$temporary",$Tag) | Out-Null
        if (!(Test-Path -LiteralPath $temporary -PathType Leaf) -or (Get-Item -LiteralPath $temporary).Length -eq 0) { throw "Source archive creation failed for $Tag" }
        [IO.File]::Move($temporary,$destination)
        Write-Host "Generated source ZIP from ${Tag}: $destination"
    } finally {
        # Only this operation's unique temporary file is removed; no recursive cleanup.
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary -Force }
    }
}
function Ensure-PublishRelease {
    param([string]$Api, [string]$Tag, [string]$Number)
    $notes = Get-PublishNotes $Tag $Number
    $json = Convert-ReleaseJson @{ tag_name=$Tag; name="AudioScope $Number - experimental Android build"; prerelease=$true; draft=$false; body=$notes }
    for ($attempt=1; $attempt -le 4; $attempt++) {
        $existing = Get-PublishResource "$Api/releases/tags/$Tag" -AllowMissing
        if ($existing) { return $existing }
        try { return Invoke-PublishRequest -Uri "$Api/releases" -Method Post -Json $json }
        catch {
            $failure = $_
            # A lost response is not proof that the POST failed. Reconcile first.
            $existing = Get-PublishResource "$Api/releases/tags/$Tag" -AllowMissing
            if ($existing) { return $existing }
            if ($attempt -eq 4 -or !(Test-TemporaryFailure $failure)) { throw $failure }
            Wait-PublishRetry $attempt $failure
        }
    }
}
function Ensure-PublishAsset {
    param($Release, [IO.FileInfo]$File)
    for ($attempt=1; $attempt -le 4; $attempt++) {
        $asset = @(Get-PublishAssets $Release | Where-Object { $_.name -ceq $File.Name })
        if ($asset.Count -gt 1) { throw "Duplicate asset name $($File.Name); inspect the release." }
        if ($asset.Count) {
            if (Test-PublishAsset $asset[0] $File) { Write-Host "Already uploaded: $($File.Name)"; return }
            if ($asset[0].state -ne 'starter' -or [long]$asset[0].size -ne 0) { throw "Asset $($File.Name) is incomplete. Inspect its upload state." }
            # GitHub can leave an empty 'starter' after a failed upload. Never delete completed assets.
            Write-Host "Removing failed zero-byte placeholder: $($File.Name)"
            try { Invoke-PublishRequest -Uri $asset[0].url -Method Delete | Out-Null }
            catch { if ((Get-HttpStatus $_) -ne 404) { throw } }
        }
        $upload = ($Release.upload_url -split '\{')[0] + '?name=' + [Uri]::EscapeDataString($File.Name)
        try {
            Write-Host "Uploading: $($File.Name)"
            $uploaded = Invoke-PublishRequest -Uri $upload -Method Post -File $File.FullName
            if (!(Test-PublishAsset $uploaded $File)) { throw "Upload did not complete: $($File.Name). Rerun to resume." }
            return
        } catch {
            $failure = $_
            $asset = @(Get-PublishAssets $Release | Where-Object { $_.name -ceq $File.Name })
            if ($asset.Count -eq 1 -and (Test-PublishAsset $asset[0] $File)) { Write-Host "Upload confirmed after interrupted response: $($File.Name)"; return }
            if ($attempt -eq 4 -or !(Test-TemporaryFailure $failure)) { throw $failure }
            Wait-PublishRetry $attempt $failure
        }
    }
}

Push-Location -LiteralPath $RepositoryPath
try {
    if ($RepositoryName -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid repository name.' }
    $tags = @(Invoke-PublishGit @('tag','--list'))
    $versions = @(Get-PublishVersions $Version $tags)
    if (!$versions.Count) { throw 'No local vMAJOR.MINOR.PATCH release tags found.' }
    if (($Apk -or $SourceZip) -and $versions.Count -ne 1) { throw 'Custom -Apk or -SourceZip needs one explicit -Version.' }
    $artifacts = [IO.Path]::GetFullPath($ArtifactsDirectory)
    $plans = @(
        foreach ($number in $versions) {
            $apkPath = if ($Apk) { [IO.Path]::GetFullPath($Apk) } else { Join-Path $artifacts "AudioScope-$number.apk" }
            $zipPath = if ($SourceZip) { [IO.Path]::GetFullPath($SourceZip) } else { Join-Path $artifacts "AudioScope-$number-source.zip" }
            [pscustomobject]@{ Version=$number; Tag="v$number"; Apk=$apkPath; SourceZip=$zipPath }
        }
    )
    Write-Host ('Versions (oldest first): ' + ($versions -join ', '))
    if ($ValidateOnly) {
        foreach ($plan in $plans) {
            Assert-PublishFile $plan.Apk -CheckApk | Out-Null
            Ensure-PublishSourceZip $plan.Tag $plan.Version $plan.SourceZip -PlanOnly
            if (Test-Path -LiteralPath $plan.SourceZip -PathType Leaf) { Assert-PublishFile $plan.SourceZip | Out-Null }
            Convert-ReleaseJson @{ tag_name=$plan.Tag; body=(Get-PublishNotes $plan.Tag $plan.Version) } | Out-Null
            Write-Host "Validated $($plan.Tag): APK, checksum when present, tagged source/archive plan, notes and JSON string."
        }
        Write-Host 'Offline validation only: no credentials, network, push, release or upload.'
        return
    }
    # Credentials remain in memory only, never in output or a file.
    $credential = Get-PublishCredential $gitExecutable $expectedAccount
    $headers = @{ Authorization="Bearer $($credential.password)"; Accept='application/vnd.github+json'; 'X-GitHub-Api-Version'='2022-11-28'; 'User-Agent'='AudioScope-release-publisher' }
    $account = Get-PublishResource 'https://api.github.com/user'
    if ($account.login -ne $expectedAccount) { throw "Authenticated as $($account.login); expected $expectedAccount. Nothing was published." }
    $api = "https://api.github.com/repos/$expectedAccount/$RepositoryName"
    $repository = Get-PublishResource $api -AllowMissing
    if (!$repository) {
        $json = Convert-ReleaseJson @{ name=$RepositoryName; description='Offline Android multitrack audio capture console'; private=$false; auto_init=$false }
        try { $repository = Invoke-PublishRequest -Uri 'https://api.github.com/user/repos' -Method Post -Json $json }
        catch { $repository = Get-PublishResource $api -AllowMissing; if (!$repository) { throw } }
    }
    if ($repository.private) { throw 'Existing repository is private. Its visibility was not changed.' }
    $remotes = @(Invoke-PublishGit @('remote'))
    if ($remotes -contains 'origin') {
        $origin = [string](Invoke-PublishGit @('remote','get-url','origin'))
        if ($origin -ne $repository.clone_url -and $origin -ne "git@github.com:$expectedAccount/$RepositoryName.git") { throw "Origin is $origin; expected $expectedAccount/$RepositoryName." }
    } else { Invoke-PublishGit @('remote','add','origin',$repository.clone_url) | Out-Null }
    # Preflight pending uploads before pushes; completed releases need no local files.
    $pending = @()
    foreach ($plan in $plans) {
        $release = Get-PublishResource "$api/releases/tags/$($plan.Tag)" -AllowMissing
        $assets = if ($release) { @(Get-PublishAssets $release) } else { @() }
        $missing = $false
        foreach ($path in @($plan.Apk,$plan.SourceZip)) {
            $name = [IO.Path]::GetFileName($path)
            $matchesAsset = @($assets | Where-Object { $_.name -ceq $name -and $_.state -eq 'uploaded' -and $_.size -gt 0 })
            if (!$matchesAsset.Count) {
                if ($path -eq $plan.SourceZip) { Ensure-PublishSourceZip $plan.Tag $plan.Version $path }
                Assert-PublishFile $path -CheckApk:($path -eq $plan.Apk) | Out-Null
                $missing=$true
            }
            elseif (Test-Path -LiteralPath $path -PathType Leaf) {
                $file = Assert-PublishFile $path -CheckApk:($path -eq $plan.Apk)
                Test-PublishAsset $matchesAsset[0] $file | Out-Null
            }
        }
        if (!$release -or $missing) { $pending += $plan }
        else { Write-Host "Release already complete: $($plan.Tag)" }
    }
    # Only committed source is pushed. The publisher itself need not be tagged as a new APK.
    Invoke-PublishGit @('-c',"credential.username=$expectedAccount",'push','origin','refs/heads/main:refs/heads/main') | Write-Host
    foreach ($plan in $plans) {
        Invoke-PublishGit @('-c',"credential.username=$expectedAccount",'push','origin',"refs/tags/$($plan.Tag):refs/tags/$($plan.Tag)") | Write-Host
        if ($pending -notcontains $plan) { continue }
        Write-Host "Publishing $($plan.Tag)..."
        $release = Ensure-PublishRelease $api $plan.Tag $plan.Version
        foreach ($path in @($plan.Apk,$plan.SourceZip)) {
            $name = [IO.Path]::GetFileName($path)
            $asset = @(Get-PublishAssets $release | Where-Object { $_.name -ceq $name -and $_.state -eq 'uploaded' -and $_.size -gt 0 })
            if ($asset.Count -and !(Test-Path -LiteralPath $path -PathType Leaf)) { Write-Host "Already uploaded (local file absent): $name"; continue }
            $file = Assert-PublishFile $path -CheckApk:($path -eq $plan.Apk)
            Ensure-PublishAsset $release $file
        }
        Write-Host "Published: $($release.html_url)"
    }
    Write-Host 'All selected tagged versions are pushed and have complete release assets.'
} catch {
    throw ("Publishing stopped: {0}`nCompleted releases/uploads are preserved. Fix the reported issue and rerun the same command to resume in version order." -f $_.Exception.Message)
} finally {
    $credentialLines=$null; $credential=$null; $headers=$null
    $env:Path=$originalPath
    [Console]::OutputEncoding=$originalEncoding
    $OutputEncoding=$originalPipeEncoding
    [Net.ServicePointManager]::SecurityProtocol=$originalProtocol
    Pop-Location
}
