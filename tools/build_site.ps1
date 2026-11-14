# Собирает сайт из site\index.template.html: картинки встраиваются в страницу.
#   site\index.html        — для страницы Claude (без doctype/head)
#   site\pages\index.html  — полноценная страница для GitHub Pages
$ErrorActionPreference = 'Stop'
$r = Resolve-Path "$PSScriptRoot\.."
$enc = New-Object Text.UTF8Encoding $false
$res = "$r\android\app_pojavlauncher\src\main\res\drawable-nodpi"
$logo = "data:image/png;base64," + [Convert]::ToBase64String([IO.File]::ReadAllBytes("$res\tnt_logo.png"))
$bg = "data:image/png;base64," + [Convert]::ToBase64String([IO.File]::ReadAllBytes("$res\tnt_background_tile.png"))
$body = [IO.File]::ReadAllText("$r\site\index.template.html", $enc).Replace("{{LOGO}}", $logo).Replace("{{BG}}", $bg)
[IO.File]::WriteAllText("$r\site\index.html", $body, $enc)

$head = '<meta charset="utf-8">' + "`n" +
    '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">' + "`n" +
    '<meta name="description" content="TNT Launcher — лаунчер Minecraft: Java Edition для Android. Скачать APK, установка, Telegram-канал.">' + "`n" +
    '<link rel="icon" href="' + $logo + '">'
$page = $body.Replace('<meta name="claude:viewport-fit-sides" content="cover">', $head)
$page = $page.Replace("* { box-sizing: border-box; }", "* { box-sizing: border-box; }`nbody { margin: 0; }`nimg { max-width: 100%; }")
$page = "<!doctype html>`n<html lang=`"ru`">`n<head>`n" + $page.Replace("</style>", "</style>`n</head>`n<body>") + "`n</body>`n</html>`n"
New-Item -ItemType Directory -Force "$r\site\pages" | Out-Null
[IO.File]::WriteAllText("$r\site\pages\index.html", $page, $enc)
Write-Host "site\index.html и site\pages\index.html собраны"
