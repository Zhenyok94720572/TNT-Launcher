# Делает иконки Android-версии из resources\label.webp и фон из resources\Beground.webp.
param(
    [string]$Res = "$PSScriptRoot\..\android\app_pojavlauncher\src\main\res",
    [string]$LogoPath = "$PSScriptRoot\..\resources\label.webp",
    [string]$BgPath = "$PSScriptRoot\..\resources\Beground.webp"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore

function Load($path) {
    $s = [IO.File]::OpenRead((Resolve-Path $path))
    $f = [Windows.Media.Imaging.BitmapDecoder]::Create($s, 'None', 'OnLoad').Frames[0]
    $s.Close()
    return $f
}

function SavePng($img, [int]$canvas, [double]$fill, [string]$out) {
    # логотип по центру прозрачного холста canvas x canvas, занимает fill от стороны
    $inner = [int]($canvas * $fill)
    $dv = New-Object Windows.Media.DrawingVisual
    $dc = $dv.RenderOpen()
    $off = ($canvas - $inner) / 2
    $dc.DrawImage($img, (New-Object Windows.Rect($off, $off, $inner, $inner)))
    $dc.Close()
    $rtb = New-Object Windows.Media.Imaging.RenderTargetBitmap($canvas, $canvas, 96, 96, [Windows.Media.PixelFormats]::Pbgra32)
    $rtb.Render($dv)
    $enc = New-Object Windows.Media.Imaging.PngBitmapEncoder
    $enc.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($rtb))
    New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
    $fs = [IO.File]::Create($out); $enc.Save($fs); $fs.Close()
}

$logoImg = Load $LogoPath
$densities = @{ 'mdpi' = 1.0; 'hdpi' = 1.5; 'xhdpi' = 2.0; 'xxhdpi' = 3.0; 'xxxhdpi' = 4.0 }
foreach ($d in $densities.Keys) {
    $dir = Join-Path $Res "mipmap-$d"
    Get-ChildItem $dir -Filter 'ic_launcher*' -ErrorAction SilentlyContinue | Remove-Item -Force
    $k = $densities[$d]
    SavePng $logoImg ([int](48 * $k)) 0.92 (Join-Path $dir 'ic_launcher.png')
    SavePng $logoImg ([int](48 * $k)) 0.80 (Join-Path $dir 'ic_launcher_round.png')
    SavePng $logoImg ([int](108 * $k)) 0.60 (Join-Path $dir 'ic_launcher_foreground.png')
}

$nodpi = Join-Path $Res 'drawable-nodpi'
SavePng $logoImg 320 1.0 (Join-Path $nodpi 'tnt_logo.png')
$bgImg = Load $BgPath
$enc = New-Object Windows.Media.Imaging.PngBitmapEncoder
$enc.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bgImg))
$fs = [IO.File]::Create((Join-Path $nodpi 'tnt_background_tile.png')); $enc.Save($fs); $fs.Close()
Write-Host "Android icons written to $Res"
