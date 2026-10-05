param(
    [string]$RepositoryName = 'AudioScope',
    [string]$Version = '0.2.0',
    [string]$Apk = (Join-Path $PSScriptRoot ("../AudioScope-$Version.apk")),
    [string]$SourceZip = (Join-Path $PSScriptRoot ("../AudioScope-$Version-source.zip"))
)
$ErrorActionPreference = 'Stop'
$expectedAccount = 'ibrahim91015'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid version.' }
$tag = "v$Version"
if ($RepositoryName -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid repository name.' }
if (-not (Test-Path -LiteralPath $Apk) -or -not (Test-Path -LiteralPath $SourceZip)) { throw 'Build APK and source ZIP first.' }
Push-Location $PSScriptRoot
try {
    if (git status --porcelain) { throw 'Commit source changes before publishing.' }
    git rev-parse --verify "refs/tags/$tag" > $null
    if ($LASTEXITCODE) { throw "Release tag $tag is missing." }
    # Capture credentials in memory only. Never print or save the token.
    $credentialLines = "protocol=https`nhost=github.com`nusername=$expectedAccount`n`n" | git credential fill
    if ($LASTEXITCODE) { throw 'Local Git credential lookup failed. Run in your normal Windows terminal and sign in with Git Credential Manager.' }
    $credential = @{}
    foreach ($line in $credentialLines) { $parts = $line -split '=',2; if ($parts.Count -eq 2) { $credential[$parts[0]] = $parts[1] } }
    if (-not $credential.password) { throw 'Git did not return a GitHub credential.' }
    $headers = @{ Authorization = "Bearer $($credential.password)"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
    $account = Invoke-RestMethod 'https://api.github.com/user' -Headers $headers
    if ($account.login -ne $expectedAccount) { throw "Authenticated as $($account.login); expected $expectedAccount. Nothing was published." }
    $api = "https://api.github.com/repos/$expectedAccount/$RepositoryName"
    try { $repo = Invoke-RestMethod $api -Headers $headers }
    catch {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
        $body = @{ name=$RepositoryName; description='Offline Android multitrack audio capture console with live sources and Material 3 controls'; private=$false; auto_init=$false } | ConvertTo-Json
        $repo = Invoke-RestMethod 'https://api.github.com/user/repos' -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    }
    if ($repo.private) { throw 'Existing repository is private. No visibility change or push was made.' }
    $origin = $null
    if ((git remote) -contains "origin") { $origin = git remote get-url origin }
    if ($origin -and $origin -ne $repo.clone_url) { throw "Origin points to $origin; expected $($repo.clone_url)." }
    if (-not $origin) { git remote add origin $repo.clone_url; if ($LASTEXITCODE) { throw 'Cannot set origin.' } }
    git -c "credential.username=$expectedAccount" push -u origin main
    if ($LASTEXITCODE) { throw 'Source push failed. No force push was attempted.' }
    git -c "credential.username=$expectedAccount" push origin $tag
    if ($LASTEXITCODE) { throw 'Tag push failed.' }
    try { $release = Invoke-RestMethod "$api/releases/tags/$tag" -Headers $headers }
    catch {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
        $notesFile = Join-Path $PSScriptRoot "docs/RELEASE-$Version.md"
        $notes = if (Test-Path -LiteralPath $notesFile) { Get-Content -LiteralPath $notesFile -Raw -Encoding UTF8 } else { "AudioScope $Version experimental Android build. See README and docs/VALIDATION.md." }
        $body = @{ tag_name=$tag; name="AudioScope $Version - experimental Android build"; prerelease=$true; body=$notes } | ConvertTo-Json
        $release = Invoke-RestMethod "$api/releases" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    }
    foreach ($path in @($Apk,$SourceZip)) {
        $file = Get-Item -LiteralPath $path
        if ($release.assets.name -contains $file.Name) { Write-Host "Already uploaded: $($file.Name)"; continue }
        $upload = ($release.upload_url -split '\{')[0] + '?name=' + [Uri]::EscapeDataString($file.Name)
        Write-Host "Uploading: $($file.Name)"
        Invoke-RestMethod $upload -Method Post -Headers $headers -ContentType 'application/octet-stream' -InFile $file.FullName > $null
    }
    Write-Host "Published: $($release.html_url)"
} finally {
    $credentialLines=$null; $credential=$null; $headers=$null
    Pop-Location
}
