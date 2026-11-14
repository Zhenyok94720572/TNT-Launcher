# Публикует сайт TNT Launcher на GitHub Pages (отдельный открытый репозиторий) и открывает репозиторий с кодом.
# Вход в GitHub берётся из Git Credential Manager (как у git push); токен не выводится.
param(
    [string]$Owner = "Zhenyok94720572",
    [string]$CodeRepo = "TNT-Launcher",
    [string]$SiteRepo = "tnt-launcher-site",
    [string]$Page = "$PSScriptRoot\..\site\pages\index.html",
    [string]$Git = ""
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (-not $Git) { $Git = Join-Path $env:USERPROFILE "tnt-android-tools\git\cmd\git.exe" }
if (-not (Test-Path $Git)) { $Git = "git" }

$cred = "protocol=https`nhost=github.com`n`n" | & $Git credential fill
$token = ($cred | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
if (-not $token) { throw "Нет сохранённого входа в GitHub. Сначала выполните git push и войдите в браузере." }
$h = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "User-Agent" = "tnt-launcher-site"; "X-GitHub-Api-Version" = "2022-11-28" }
$api = "https://api.github.com"

function Api($Method, $Url, $Body) {
    $p = @{ Method = $Method; Uri = $Url; Headers = $h }
    if ($Body) { $p.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5)); $p.ContentType = "application/json; charset=utf-8" }
    return Invoke-RestMethod @p
}
function TryGet($Url) {
    try { return Api GET $Url $null } catch { if ($_.Exception.Response.StatusCode.value__ -eq 404) { return $null } else { throw } }
}

# 1. Репозиторий с кодом — открытый (LGPL: исходники доступны вместе с APK)
Write-Host "Открываю $CodeRepo..."
Api PATCH "$api/repos/$Owner/$CodeRepo" @{ private = $false; homepage = "https://$($Owner.ToLower()).github.io/$SiteRepo/";
    description = "TNT Launcher — Minecraft: Java Edition launcher for Android" } | Out-Null

# 2. Отдельный открытый репозиторий для сайта
if (-not (TryGet "$api/repos/$Owner/$SiteRepo")) {
    Write-Host "Создаю $SiteRepo..."
    Api POST "$api/user/repos" @{ name = $SiteRepo; private = $false; auto_init = $true;
        description = "Сайт TNT Launcher"; homepage = "https://$($Owner.ToLower()).github.io/$SiteRepo/" } | Out-Null
    Start-Sleep -Seconds 3
}

function PutFile($Path, $Bytes, $Message) {
    $cur = TryGet "$api/repos/$Owner/$SiteRepo/contents/$Path"
    $body = @{ message = $Message; content = [Convert]::ToBase64String($Bytes) }
    if ($cur) { $body.sha = $cur.sha }
    Api PUT "$api/repos/$Owner/$SiteRepo/contents/$Path" $body | Out-Null
}
Write-Host "Загружаю страницу..."
PutFile "index.html" ([IO.File]::ReadAllBytes((Resolve-Path $Page))) "Сайт TNT Launcher"
$readme = "# Сайт TNT Launcher`n`nhttps://$($Owner.ToLower()).github.io/$SiteRepo/`n`nКод лаунчера: https://github.com/$Owner/$CodeRepo`n`nTelegram: https://t.me/tntlaunchermobile`n"
PutFile "README.md" ([Text.Encoding]::UTF8.GetBytes($readme)) "README"

# 3. GitHub Pages из ветки main
if (-not (TryGet "$api/repos/$Owner/$SiteRepo/pages")) {
    Write-Host "Включаю GitHub Pages..."
    Api POST "$api/repos/$Owner/$SiteRepo/pages" @{ source = @{ branch = "main"; path = "/" } } | Out-Null
}
$pages = Api GET "$api/repos/$Owner/$SiteRepo/pages" $null
Write-Host "Сайт: $($pages.html_url)"
Write-Host "Код:  https://github.com/$Owner/$CodeRepo"
Write-Host "Готово. Первая публикация Pages занимает 1-2 минуты."
