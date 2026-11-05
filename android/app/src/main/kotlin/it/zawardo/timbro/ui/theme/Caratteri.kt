package it.zawardo.timbro.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.zawardo.timbro.R

/**
 * Unbounded per titoli e parole grandi, Inter per il testo, JetBrains Mono per ore, timer e impronte. File variabili
 * nell'app (google/fonts, licenza OFL in assets/licenze): niente caratteri scaricati a runtime né servizi di Google.
 */
@OptIn(ExperimentalTextApi::class)
private fun variabile(res: Int, peso: Int) =
    Font(res, FontWeight(peso), variationSettings = FontVariation.Settings(FontVariation.weight(peso)))

val Unbounded = FontFamily(variabile(R.font.unbounded, 500), variabile(R.font.unbounded, 700), variabile(R.font.unbounded, 900))
val Inter = FontFamily(listOf(400, 500, 600, 700, 800).map { variabile(R.font.inter, it) })
val JetBrainsMono = FontFamily(listOf(500, 600, 700).map { variabile(R.font.jetbrains_mono, it) })

/** Stili di testo, per dimensione (come nel canvas, in dp ≈ px a 390 di larghezza). */
object Testo {
    private val base = TextStyle(fontFamily = Inter, color = Colori.Inchiostro)
    private val titolo = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Black, color = Colori.Inchiostro)

    // spaziatura appena positiva: in Unbounded Black la E ha il fianco obliquo e, stretta, si salda alla I di RIENTRO
    val Parola64 = titolo.copy(fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = 0.025.em)
    val Display34 = titolo.copy(fontSize = 34.sp, lineHeight = 38.sp)
    val Titolo30 = titolo.copy(fontSize = 30.sp, lineHeight = 36.sp)
    val Titolo28 = titolo.copy(fontSize = 28.sp, lineHeight = 32.sp)
    val Titolo26 = titolo.copy(fontSize = 26.sp, lineHeight = 30.sp)
    val Titolo22 = titolo.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val Titolo15 = titolo.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    val Marchio18 = titolo.copy(fontSize = 18.sp, lineHeight = 22.sp)
    val Settimana12 = titolo.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, color = Colori.Testo2)
    val Bottone13 = titolo.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)

    val Corpo16 = base.copy(fontSize = 16.sp, lineHeight = 22.sp)
    val Corpo15 = base.copy(fontSize = 15.sp, lineHeight = 21.sp)
    val Corpo14 = base.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val Piccolo13 = base.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Colori.Testo2)
    val Piccolo12 = base.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Colori.Testo2)
    val Etichetta11 = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.08.em)
    val Sezione13 = base.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.08.em, color = Colori.Testo2)

    val Mono21 = TextStyle(fontFamily = JetBrainsMono, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = Colori.Inchiostro)
    val Mono18 = Mono21.copy(fontSize = 18.sp, lineHeight = 22.sp)
    val Mono16 = Mono21.copy(fontSize = 16.sp, lineHeight = 20.sp)
    val Mono15 = Mono21.copy(fontSize = 15.sp, lineHeight = 19.sp)
    val Mono14 = Mono21.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val Mono13 = Mono14.copy(fontSize = 13.sp, lineHeight = 18.sp)
    val Mono12 = Mono14.copy(fontSize = 12.sp, lineHeight = 16.sp, color = Colori.Testo2)
}
