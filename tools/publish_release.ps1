# Публикует релиз TNT Launcher на GitHub: релиз в репозитории с кодом и APK в публичном репозитории релизов.
# Вход в GitHub берётся из Git Credential Manager (тот же, что и для git push); токен не выводится.
param(
    [Parameter(Mandatory = $true)][string]$Tag,          # например v1.1
    [Parameter(Mandatory = $true)][string]$Title,        # например "TNT Launcher 1.1"
    [Parameter(Mandatory = $true)][string]$NotesFile,    # текст релиза (Markdown, UTF-8)
    [Parameter(Mandatory = $true)][string]$Apk,          # путь к APK
    [string]$Owner = "Zhenyok94720572",
    [string]$CodeRepo = "TNT-Launcher",
    [string]$PublicRepo = "tnt-launcher-releases",
    [string]$Git = ""
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (-not $Git) { $Git = Join-Path $env:USERPROFILE "tnt-android-tools\git\cmd\git.exe" }
if (-not (Test-Path $Git)) { $Git = "git" }

# --- токен из Git Credential Manager (не печатаем) ---
$cred = "protocol=https`nhost=github.com`n`n" | & $Git credential fill
$token = ($cred | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
if (-not $token) { throw "Нет сохранённого входа в GitHub. Сначала выполните git push и войдите в браузере." }
$h = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "User-Agent" = "tnt-launcher-release"; "X-GitHub-Api-Version" = "2022-11-28" }
$api = "https://api.github.com"

function Api($Method, $Url, $Body) {
    $p = @{ Method = $Method; Uri = $Url; Headers = $h }
    if ($Body) { $p.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5)); $p.ContentType = "application/json; charset=utf-8" }
    return Invoke-RestMethod @p
}

function TryGet($Url) {
    try { return Api GET $Url $null } catch { if ($_.Exception.Response.StatusCode.value__ -eq 404) { return $null } else { throw } }
}

$notes = [IO.File]::ReadAllText((Resolve-Path $NotesFile), [Text.Encoding]::UTF8)
$apkPath = (Resolve-Path $Apk).Path
$assetName = "TNTLauncher-" + $Tag.TrimStart('v') + ".apk"

# --- публичный репозиторий только для APK ---
if (-not (TryGet "$api/repos/$Owner/$PublicRepo")) {
    Write-Host "Создаю публичный репозиторий $PublicRepo..."
    Api POST "$api/user/repos" @{ name = $PublicRepo; private = $false; auto_init = $true;
        description = "TNT Launcher — Minecraft: Java Edition launcher for Android. APK downloads." } | Out-Null
    Start-Sleep -Seconds 3
    $readme = "# TNT Launcher`n`nЛаунчер Minecraft: Java Edition для Android (телефоны и планшеты).`n`n" +
        "**Скачать:** [последний релиз](https://github.com/$Owner/$PublicRepo/releases/latest) — файл ``TNTLauncher-*.apk``.`n`n" +
        "Официальный Telegram-канал: https://t.me/tntlaunchermobile`n`n" +
        "Авторы: Zhenyok — разработчик, Molly — моральная поддержка. На основе Amethyst (LGPLv3).`n"
    $cur = Api GET "$api/repos/$Owner/$PublicRepo/contents/README.md" $null
    Api PUT "$api/repos/$Owner/$PublicRepo/contents/README.md" @{ message = "README"; sha = $cur.sha;
        content = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($readme)) } | Out-Null
}

function Publish($Repo, $Target) {
    $rel = TryGet "$api/repos/$Owner/$Repo/releases/tags/$Tag"
    if (-not $rel) {
        $body = @{ tag_name = $Tag; name = $Title; body = $notes; draft = $false; prerelease = $false }
        if ($Target) { $body.target_commitish = $Target }
        $rel = Api POST "$api/repos/$Owner/$Repo/releases" $body
    }
    $old = $rel.assets | Where-Object { $_.name -eq $assetName }
    if ($old) { Api DELETE "$api/repos/$Owner/$Repo/releases/assets/$($old.id)" $null | Out-Null }
    Write-Host "Загружаю $assetName в $Repo ($([math]::Round((Get-Item $apkPath).Length/1MB)) МБ)..."
    $up = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($rel.id)/assets?name=$assetName"
    $asset = Invoke-RestMethod -Method POST -Uri $up -Headers $h -ContentType "application/vnd.android.package-archive" -InFile $apkPath -TimeoutSec 3600
    Write-Host "  релиз: $($rel.html_url)"
    Write-Host "  APK:   $($asset.browser_download_url)"
}

Publish $CodeRepo "main"
Publish $PublicRepo $null
Write-Host "Готово."
