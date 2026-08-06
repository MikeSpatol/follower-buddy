Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$W = 48; $H = 72
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

# Palette: enough contrast to read on RuneLite's dark sidebar AND on the
# hub's light listing page.
$OUT   = Hex "14120f"
$SKIN  = Hex "d0a074"
$SKINS = Hex "a97b52"
$HAIR  = Hex "7a4f2b"
$HAIRS = Hex "563519"
$TUNIC = Hex "4f83bd"
$TUNS  = Hex "365f92"
$BELT  = Hex "4a3524"
$LEG   = Hex "434a57"
$LEGD  = Hex "2f343d"
$BOOT  = Hex "231e19"
$BUB   = Hex "f6f3e6"
$BUBS  = Hex "d2cbb2"
$DOT   = Hex "26221c"

# ---------------------------------------------------------------- speech bubble
# The follower TALKS - that is the plugin in one glyph. Top right, tail
# angling down at the head.
Box 23 2 24 2 $OUT
Box 23 17 24 2 $OUT
Box 22 3 2 15 $OUT
Box 45 3 2 15 $OUT
Box 24 4 21 13 $BUB
Box 24 15 21 2 $BUBS
# tail, stepping down-left toward the head
Box 26 19 7 2 $OUT
Box 25 21 5 2 $OUT
Box 24 23 3 2 $OUT
Box 26 19 5 2 $BUB
Box 25 21 3 2 $BUB
# three dots
Box 28 9 3 3 $DOT
Box 33 9 3 3 $DOT
Box 38 9 3 3 $DOT

# ------------------------------------------------------------------- the figure
# Blocky like the game's own player model, with every limb outlined so the
# silhouette still reads when the hub scales this down.

# --- head
Box 10 22 15 15 $OUT
Box 11 23 13 13 $SKIN
Box 11 33 13 2 $SKINS
# hair: cap, then short sides framing the face
Box 11 23 13 5 $HAIR
Box 11 23 13 2 $HAIRS
Box 11 28 2 4 $HAIR
Box 22 28 2 4 $HAIR
# face: wide-set eyes and a small mouth read as friendly, not scowling
Box 14 29 2 2 $DOT
Box 19 29 2 2 $DOT
Box 16 33 3 1 $SKINS

# --- neck
Box 15 37 5 3 $OUT
Box 16 37 3 2 $SKINS

# --- torso
Box 10 39 15 16 $OUT
Box 11 40 13 14 $TUNIC
Box 11 48 13 2 $TUNS
Box 11 51 13 3 $BELT

# --- arms, OUTSIDE the torso outline so they never merge into one blue mass
Box 5 40 6 15 $OUT
Box 24 40 6 15 $OUT
Box 6 41 4 10 $TUNIC
Box 25 41 4 10 $TUNIC
Box 6 48 4 3 $TUNS
Box 25 48 4 3 $TUNS
Box 6 51 4 3 $SKIN
Box 25 51 4 3 $SKIN

# --- legs
Box 10 55 8 14 $OUT
Box 17 55 8 14 $OUT
Box 11 56 6 10 $LEG
Box 18 56 6 10 $LEG
Box 11 61 6 5 $LEGD
Box 18 61 6 5 $LEGD
Box 11 66 6 2 $BOOT
Box 18 66 6 2 $BOOT

$g.Dispose()
$out = "C:\Users\MikeS\OneDrive\Claude\osrs-follower-plugin\icon.png"
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"wrote $out ($((Get-Item $out).Length) bytes)"
