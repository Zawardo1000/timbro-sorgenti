"""Icona di Timbro dall'immagine generata (play/icona-sorgente.jpeg): segno bianco estratto (alfa) + fondo a sfumatura.

Uso, da android/: python play/genera_icona.py play/icona-sorgente.jpeg <cartella>
Uscite (cartella di destinazione = argv[2]):
  segno.png                       segno bianco a risoluzione originale, sul quadrato dell'icona
  mipmap-*/ic_launcher_foreground.png   primo piano dell'icona adattiva (108dp, quadrato dell'icona = 72dp)
  icona-512.png                   icona per Google Play (quadrato pieno, Play arrotonda da sé)
  anteprima.png                   come appare con maschera tonda e squircle, più l'icona di Play
"""

import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

src, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
im = Image.open(src).convert("RGB")
px = im.load()

X0, X1, Y0, Y1 = 492, 915, 148, 587  # quadrato dell'icona nell'immagine (analizza.py)
SW, SH = X1 - X0 + 1, Y1 - Y0 + 1
# sfumatura misurata riga per riga (analizza.py): lineare dall'alto in basso
TOP = (12, 107, 206, 172)  # r, g, b alla riga 172
BOTTOM = (37, 197, 169, 552)


def fondo(y: float) -> tuple[float, float, float]:
    t = (y - TOP[3]) / (BOTTOM[3] - TOP[3])
    return tuple(TOP[i] + (BOTTOM[i] - TOP[i]) * t for i in range(3))


def colore(y: float) -> str:
    return "#%02X%02X%02X" % tuple(max(0, min(255, round(c))) for c in fondo(y))


TOP_HEX, BOTTOM_HEX = colore(Y0), colore(Y1)

# alfa del segno: quanto il pixel va dal fondo della sua riga verso il bianco, solo nella zona del segno (lontano
# dagli angoli arrotondati, dove il fondo chiaro dell'immagine sembrerebbe bianco)
alpha = Image.new("L", (SW, SH), 0)
ap = alpha.load()
for y in range(195, 546):
    b = fondo(y)
    for x in range(535, 895):
        p = px[x, y]
        a = sum((p[i] - b[i]) / (255 - b[i]) for i in range(3)) / 3
        a = 0.0 if a < 0.10 else 1.0 if a > 0.90 else (a - 0.10) / 0.80
        ap[x - X0, y - Y0] = round(a * 255)
alpha = alpha.filter(ImageFilter.MedianFilter(3))  # rumore del JPEG
white = Image.new("L", (SW, SH), 255)
segno = Image.merge("RGBA", (white, white, white, alpha))
segno.save(out / "segno.png")


def glifo(size_square: float) -> Image.Image:
    """Il segno scalato perché il quadrato dell'icona (424 px di larghezza) diventi size_square pixel."""
    k = size_square / SW
    return segno.resize((round(SW * k), round(SH * k)), Image.LANCZOS)


def sfumatura(w: int, h: int, top_y: float, bottom_y: float) -> Image.Image:
    """Fondo: colore di Y0 alla riga top_y, di Y1 alla riga bottom_y, costante oltre."""
    g = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = min(1.0, max(0.0, (y - top_y) / (bottom_y - top_y)))
        d.line([(0, y), (w, y)], fill=tuple(round(fondo(Y0 + t * (Y1 - Y0))[i]) for i in range(3)))
    return g


# primo piano adattivo: 108dp, il quadrato dell'icona occupa i 72dp visibili, centrato
DENSITA = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for nome, d in DENSITA.items():
    lato = round(108 * d)
    g = glifo(72 * d)
    tela = Image.new("RGBA", (lato, lato), (0, 0, 0, 0))
    tela.alpha_composite(g, (round((lato - g.width) / 2), round((lato - g.height) / 2)))
    (out / f"mipmap-{nome}").mkdir(exist_ok=True)
    tela.save(out / f"mipmap-{nome}" / "ic_launcher_foreground.png", optimize=True)

# Google Play: 512 px, il quadrato dell'icona = tutta l'immagine (Play applica da sé gli angoli)
play = sfumatura(512, 512, 0, 511).convert("RGBA")
g = glifo(512)
play.alpha_composite(g, (round((512 - g.width) / 2), round((512 - g.height) / 2)))
play.convert("RGB").save(out / "icona-512.png", optimize=True)

# anteprima: icona adattiva (432 px = 108dp a xxxhdpi) con maschera tonda e squircle, e quella di Play
fg = Image.open(out / "mipmap-xxxhdpi" / "ic_launcher_foreground.png")
adattiva = sfumatura(432, 432, (432 - 72 * 4 * SH / SW) / 2, (432 + 72 * 4 * SH / SW) / 2).convert("RGBA")
adattiva.alpha_composite(fg)
visibile = adattiva.crop((72, 72, 360, 360))  # i 72dp centrali
ant = Image.new("RGBA", (288 * 3 + 80, 288 + 40), (30, 30, 34, 255))
for i, forma in enumerate(("tonda", "squircle", "play")):
    if forma == "play":
        img = play.resize((288, 288), Image.LANCZOS).convert("RGBA")
        mask = Image.new("L", (288, 288), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, 287, 287), radius=58, fill=255)
    else:
        img = visibile
        mask = Image.new("L", (288, 288), 0)
        if forma == "tonda":
            ImageDraw.Draw(mask).ellipse((0, 0, 287, 287), fill=255)
        else:
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, 287, 287), radius=100, fill=255)
    ant.paste(img, (20 + i * (288 + 20), 20), ImageChops.multiply(mask, img.getchannel("A")))
ant.save(out / "anteprima.png")
print("fondo", TOP_HEX, "->", BOTTOM_HEX, "| quadrato in dp:", 72, "x", round(72 * SH / SW, 1))
