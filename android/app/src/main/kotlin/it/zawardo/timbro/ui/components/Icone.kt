package it.zawardo.timbro.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Le icone del prototipo, ridisegnate dai tracciati SVG del mockup (24×24, solo contorno). Il colore lo decide chi le
 * usa (tint), quindi il tracciato è nero.
 */
private fun contorno(nome: String, larghezza: Float = 1.8f, vararg tracciati: String) =
    ImageVector.Builder(nome, 24.dp, 24.dp, 24f, 24f).apply {
        tracciati.forEach {
            addPath(
                addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = larghezza,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun pieno(nome: String, tracciato: String) =
    ImageVector.Builder(nome, 24.dp, 24.dp, 24f, 24f).apply { addPath(addPathNodes(tracciato), fill = SolidColor(Color.Black)) }.build()

private fun cerchio(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

object Icone {
    val Timbra = contorno("timbra", 1.8f, cerchio(12f, 12f, 8f), cerchio(12f, 12f, 3f))
    val Storico = contorno("storico", 1.8f, "M4 6h16M4 12h16M4 18h10")
    val Impostazioni = contorno(
        "impostazioni", 1.8f, cerchio(12f, 12f, 3f),
        "M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1",
    )
    val Video = contorno("video", 1.6f, "M15 10l5-3v10l-5-3", "M5 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z")
    val Fatto = contorno("fatto", 2.2f, "M5 12l5 5 9-10")
    val Attesa = contorno("attesa", 1.8f, cerchio(12f, 12f, 8f), "M12 8v4l3 2")
    val AttesaBreve = contorno("attesa-breve", 2.2f, "M12 7v5l3 2")
    val Link = contorno(
        "link", 2f, "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    )
    val Indietro = contorno("indietro", 2f, "M15 6l-6 6 6 6")
    val Riproduci = pieno("riproduci", "M8 5v14l11-7z")
    val Lucchetto = contorno("lucchetto", 2f, "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-10a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val Impronta = contorno(
        "impronta", 1.8f,
        "M12 11c0 3-1 5-2 7M8 7.5A5 5 0 0 1 17 10c0 2.5-.4 4.6-1.2 6.5M5.5 10a6.5 6.5 0 0 1 .8-3.2M14 13c0 2-.4 3.6-1 5M18.5 6.5A8 8 0 0 1 20 11",
    )
    // novità rispetto al mockup: attestazione hardware e reti Wi-Fi (docs/PROGETTO.md §19)
    val Scudo = contorno("scudo", 1.8f, "M12 3l7 3v5c0 5-3 8-7 10c-4-2-7-5-7-10V6z", "M9 12l2 2 4-4")
    val Wifi = contorno("wifi", 1.8f, "M5 9.5a10 10 0 0 1 14 0M7.8 12.6a6 6 0 0 1 8.4 0M10.6 15.6a2 2 0 0 1 2.8 0", "M12 18.5v.01")
    // stile C: sveglia del rientro, esiti della catena di stato
    val Campanella = contorno("campanella", 2.2f, "M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20a2 2 0 0 0 4 0")
    val FattoGrosso = contorno("fatto-grosso", 3f, "M5 12l5 5 9-10")
    val Orologio = contorno("orologio", 3f, "M12 7v5l3 2")
    val Avviso = contorno("avviso", 3f, "M12 7v6", "M12 17v.01")
    val Calendario = contorno(
        "calendario", 2f, "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-14a2 2 0 0 1-2-2v-12a2 2 0 0 1 2-2z", "M3 10h18", "M8 3v4M16 3v4",
    )
}
