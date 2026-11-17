$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetDirectory = Join-Path $PSScriptRoot 'assets'
$resourceDirectory = Join-Path $PSScriptRoot '../src/main/resources/com/netpulse/ui'
New-Item -ItemType Directory -Force -Path $assetDirectory, $resourceDirectory | Out-Null
$images = @()
foreach ($size in @(16, 32, 48, 64, 128, 256)) {
    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.Clear([System.Drawing.Color]::FromArgb(13, 18, 26))
        $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(65, 218, 174), ($size * 0.065))
        $pen.StartCap = $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        try {
            $points = [System.Drawing.PointF[]]@(
                [System.Drawing.PointF]::new($size * 0.12, $size * 0.54),
                [System.Drawing.PointF]::new($size * 0.31, $size * 0.54),
                [System.Drawing.PointF]::new($size * 0.42, $size * 0.27),
                [System.Drawing.PointF]::new($size * 0.58, $size * 0.73),
                [System.Drawing.PointF]::new($size * 0.69, $size * 0.46),
                [System.Drawing.PointF]::new($size * 0.88, $size * 0.46))
            $graphics.DrawLines($pen, $points)
        } finally { $pen.Dispose() }
        if ($size -eq 256) {
            $bitmap.Save((Join-Path $resourceDirectory 'netpulse.png'), [System.Drawing.Imaging.ImageFormat]::Png)
        }
        $stream = New-Object System.IO.MemoryStream
        try {
            $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
            $images += ,@{ Size = $size; Bytes = $stream.ToArray() }
        } finally { $stream.Dispose() }
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
$file = [System.IO.File]::Create((Join-Path $assetDirectory 'netpulse.ico'))
$writer = New-Object System.IO.BinaryWriter($file)
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$images.Count)
    $offset = 6 + 16 * $images.Count
    foreach ($entry in $images) {
        $dimension = if ($entry.Size -eq 256) { 0 } else { $entry.Size }
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
        $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$entry.Bytes.Length); $writer.Write([uint32]$offset)
        $offset += $entry.Bytes.Length
    }
    foreach ($entry in $images) { $writer.Write([byte[]]$entry.Bytes) }
} finally { $writer.Dispose(); $file.Dispose() }
Write-Output 'Generated the original NetPulse pulse icon (no external artwork or fonts).'
