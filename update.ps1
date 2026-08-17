<#
    One command to update the server.

    Right-click this file and choose "Run with PowerShell", or run  .\update.ps1  from a terminal
    in this folder.

    It exists because config.yaml is the one file both you and the update touch, so a plain
    "git pull" stops with a merge error roughly every time either side edits it. That has already
    happened twice, and the second time it silently left an old broken version of the server
    running - the pull failed, the build succeeded, and nothing said the two were unrelated.

    So this reads the settings you have changed, updates the code, puts your settings back, and
    only then rebuilds. If a setting cannot be carried across because it no longer exists, it says
    so rather than dropping it quietly.
#>

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Say([string]$text)  { Write-Host $text }
function Good([string]$text) { Write-Host "  $text" -ForegroundColor Green }
function Warn([string]$text) { Write-Host "  $text" -ForegroundColor Yellow }
function Bad([string]$text)  { Write-Host "  $text" -ForegroundColor Red }

# Reads "KEY: value" lines into a hashtable. Deliberately ignores comments and structure - this
# is only ever used to compare two versions of the same file, key by key.
function Read-Settings([string[]]$lines) {
    $settings = @{}
    foreach ($line in $lines) {
        if ($line -match '^\s{2,}([A-Z][A-Z0-9_]*)\s*:\s*(.*?)\s*(#.*)?$') {
            $value = $Matches[2].Trim()
            if ($value -ne '') { $settings[$Matches[1]] = $value }
        }
    }
    return $settings
}

Say ''
Say '=== Updating your MapleStory server ==='
Say ''

# --- 1. Work out which settings you have changed ------------------------------------------------
Say 'Checking your settings...'
$yours    = Read-Settings (Get-Content 'config.yaml')
$original = Read-Settings ((git show HEAD:config.yaml) -split "`n")

$changed = @{}
foreach ($key in $yours.Keys) {
    if ($original.ContainsKey($key) -and $original[$key] -ne $yours[$key]) {
        $changed[$key] = $yours[$key]
    }
}

if ($changed.Count -eq 0) {
    Good 'No settings of yours differ from the last update. Nothing to preserve.'
} else {
    Good "$($changed.Count) setting(s) of yours will be carried across:"
    foreach ($key in ($changed.Keys | Sort-Object)) { Say "      $key : $($changed[$key])" }
}

Copy-Item 'config.yaml' 'config.yaml.backup' -Force
Good 'Saved a copy as config.yaml.backup, just in case.'

# --- 2. Update the code -------------------------------------------------------------------------
Say ''
Say 'Downloading the latest version...'
git checkout -- config.yaml
$pull = git pull 2>&1
$pullFailed = $LASTEXITCODE -ne 0

if ($pullFailed) {
    Bad 'Could not download the update. Nothing has been changed. The reason was:'
    Say ''
    $pull | ForEach-Object { Say "    $_" }
    Say ''
    Bad 'Your server is still running the version it was before. Send the above to Claude.'
    exit 1
}
Good 'Code updated.'

# --- 3. Put your settings back ------------------------------------------------------------------
if ($changed.Count -gt 0) {
    Say ''
    Say 'Restoring your settings...'
    $config = Get-Content 'config.yaml'
    $missing = @()

    foreach ($key in $changed.Keys) {
        $applied = $false
        for ($i = 0; $i -lt $config.Count; $i++) {
            if ($config[$i] -match "^(\s{2,}$key\s*:\s*)(.*?)(\s*#.*)?$") {
                $config[$i] = $Matches[1] + $changed[$key] + $Matches[3]
                $applied = $true
                break
            }
        }
        if (-not $applied) { $missing += $key }
    }

    Set-Content 'config.yaml' $config
    Good "Restored $($changed.Count - $missing.Count) setting(s)."
    if ($missing.Count -gt 0) {
        Warn "These settings no longer exist and were left out: $($missing -join ', ')"
        Warn 'That is usually fine - it means the feature changed. Your old file is config.yaml.backup.'
    }
}

# --- 4. Rebuild and restart ---------------------------------------------------------------------
Say ''
Say 'Rebuilding and restarting (this takes a few minutes)...'
docker compose up -d --build maplestory
if ($LASTEXITCODE -ne 0) {
    Bad 'The rebuild failed. Your server may be stopped. Send the output above to Claude.'
    exit 1
}

# --- 5. Wait and report in plain English --------------------------------------------------------
Say ''
Say 'Waiting for the server to come up...'
$deadline = (Get-Date).AddMinutes(5)
$outcome  = 'timeout'

while ((Get-Date) -lt $deadline) {
    $log = docker compose logs --tail 400 maplestory 2>&1 | Out-String
    if ($log -match 'Cosmic is now online')          { $outcome = 'online';  break }
    if ($log -match 'Failed to run database migrations') { $outcome = 'migration'; break }
    if ($log -match 'Exception in thread "main"')    { $outcome = 'crash';   break }
    Start-Sleep -Seconds 5
}

Say ''
switch ($outcome) {
    'online' {
        $bots = ([regex]'Bot bodies: (\d+) of (\d+) in the world').Match(
                    (docker compose logs --tail 400 maplestory 2>&1 | Out-String))
        Good '======================================='
        Good ' Your server is running. Go ahead and log in.'
        if ($bots.Success) { Good " $($bots.Groups[1].Value) of $($bots.Groups[2].Value) characters are in the world." }
        Good '======================================='
    }
    'migration' {
        Bad 'The server could not update its database and did not start.'
        Bad 'Run this and send Claude the result:   docker compose logs --tail 80 maplestory'
    }
    'crash' {
        Bad 'The server hit an error while starting.'
        Bad 'Run this and send Claude the result:   docker compose logs --tail 80 maplestory'
    }
    default {
        Warn 'The server is taking longer than expected. It may still be starting.'
        Warn 'Check with:   docker compose logs -f maplestory'
    }
}
Say ''
