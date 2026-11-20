# Regenerates res/lumina.ico (256 px PNG-in-ICO) - same drawing as TrayApp.MakeIcon.
# Usage: powershell -ExecutionPolicy Bypass -File res/make-icon.ps1
Add-Type -AssemblyName System.Drawing
$size = 256; $s = $size / 32.0
$bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'; $g.Clear([System.Drawing.Color]::Transparent)
$r = New-Object System.Drawing.RectangleF (3*$s), (3*$s), (26*$s), (26*$s)
$d = 16 * $s
$p = New-Object System.Drawing.Drawing2D.GraphicsPath
$p.AddArc($r.Left, $r.Top, $d, $d, 180, 90); $p.AddArc($r.Right - $d, $r.Top, $d, $d, 270, 90)
$p.AddArc($r.Right - $d, $r.Bottom - $d, $d, $d, 0, 90); $p.AddArc($r.Left, $r.Bottom - $d, $d, $d, 90, 90)
$p.CloseFigure()
$g.DrawPath((New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(110,255,61,0)), (6*$s)), $p)
$g.DrawPath((New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,255,120,50)), (3*$s)), $p)
$g.DrawPath((New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255,255,200,150)), (1.2*$s)), $p)
$g.Dispose()
$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
$png = $ms.ToArray()
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $out
$w.Write([UInt16]0); $w.Write([UInt16]1); $w.Write([UInt16]1)            # ICONDIR
$w.Write([Byte]0); $w.Write([Byte]0); $w.Write([Byte]0); $w.Write([Byte]0) # 256x256, no palette
$w.Write([UInt16]1); $w.Write([UInt16]32); $w.Write([UInt32]$png.Length); $w.Write([UInt32]22)
$w.Write($png); $w.Flush()
[System.IO.File]::WriteAllBytes((Join-Path $PSScriptRoot 'lumina.ico'), $out.ToArray())
