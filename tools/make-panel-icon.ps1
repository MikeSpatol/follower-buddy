Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

# The sidebar nav button, 16x16. The same character as icon.png, but a
# toolbar glyph has a sixth of the height to work with: the speech bubble,
# the belt, the hands and the face detail all fall below one pixel and are
# dropped. What survives is the silhouette - hair, tunic, two legs - which
# is what makes it recognisable next to the hub icon.
$W = 16; $H = 16
$bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.Clear([System.Drawing.Color]::Transparent)

function Hex([string]$hex) {
  $r = [Convert]::ToInt32($hex.Substring(0,2),16)
  $gg = [Convert]::ToInt32($hex.Substring(2,2),16)
  $b = [Convert]::ToInt32($hex.Substring(4,2),16)
  return [System.Drawing.Color]::FromArgb(255, $r, $gg, $b)
}
function Box($x, $y, $w, $h, $col) {
  $brush = New-Object System.Drawing.SolidBrush($col)
  $g.FillRectangle($brush, [int]$x, [int]$y, [int]$w, [int]$h)
  $brush.Dispose()
}

# Same palette as icon.png, brightened a little: the nav bar is dark and a
# 16px glyph has no room for shading to carry the read.
$OUT   = Hex "14120f"
$SKIN  = Hex "d8a87c"
$HAIR  = Hex "875832"
$TUNIC = Hex "5a8fca"
$TUNS  = Hex "3d6a9e"
$LEG   = Hex "4a515e"
$DOT   = Hex "26221c"

# --- head (with hair cap)
Box 4 1 8 7 $OUT
Box 5 2 6 5 $SKIN
Box 5 2 6 2 $HAIR
# eyes: one pixel each is all there is room for
Box 6 5 1 1 $DOT
Box 9 5 1 1 $DOT

# --- torso
Box 4 8 8 5 $OUT
Box 5 9 6 3 $TUNIC
Box 5 11 6 1 $TUNS

# --- arms, kept outside the torso outline so the shape stays legible
Box 2 8 2 5 $OUT
Box 12 8 2 5 $OUT
Box 2 9 1 3 $TUNIC
Box 13 9 1 3 $TUNIC

# --- legs
Box 4 13 4 3 $OUT
Box 8 13 4 3 $OUT
Box 5 13 2 2 $LEG
Box 9 13 2 2 $LEG

$g.Dispose()
$out = "C:\Users\MikeS\OneDrive\Claude\osrs-follower-plugin\src\main\resources\com\follower\panel-icon.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"wrote $out ($((Get-Item $out).Length) bytes)"
