package it.zawardo.timbro.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette "C · Segnale" (canvas "Timbro – direzioni scure", docs/PROGETTO.md §20): fondo quasi nero, un colore per
 * tipo (menta = entrata, arancio = uscita) con il testo scuro sopra, giallo per contestate e attese, azzurro per i
 * video da tenere. I nomi restano quelli del primo prototipo dove il ruolo è lo stesso.
 */
object Colori {
    val Fondo = Color(0xFF0D0D10)
    val Superficie = Color(0xFF17171C)
    val Rilievo = Color(0xFF24242C)        // pillole spente, binario della barra del giorno, campi
    val Bordo = Color(0xFF26262E)
    val Divisore = Color(0xFF26262E)
    val BordoControllo = Color(0xFF3A3A44)

    val Inchiostro = Color(0xFFFFFFFF)     // testo principale
    val Testo2 = Color(0xFFA8A8B3)
    val Testo3 = Color(0xFFD6D6DE)
    val Disattivo = Color(0xFF6E6E7A)
    val SuColore = Color(0xFF0D0D10)       // testo e icone sopra menta, arancio, giallo e bianco

    val Entrata = Color(0xFF3DFFB0)
    val Uscita = Color(0xFFFF7A1A)
    val Giallo = Color(0xFFFFE14D)
    val Azzurro = Color(0xFF8ED0FF)
    val Rec = Color(0xFFFF3B3B)
    val Pericolo = Color(0xFFFF6B6B)

    val Accento = Entrata                  // esiti positivi (marcata, integro)
    val AccentoChiaro = Color(0xFF12352A)
    val Contestata = Giallo
    val ContestataChiaro = Color(0xFF3A3414)
    val DaTenereChiaro = Color(0xFF14303F)

    // Registrazione
    val CameraFondo = Color(0xFF000000)
    val CameraPannello = Color(0xFF121216)
    val CameraChip = Color(0xFF26262E)
    val LcdFondo = Color(0xFF1F2A22)
    val LcdBordo = Color(0xFF36463A)
    val LcdTesto = Color(0xFFC9F7C4)
    val LcdTesto2 = Color(0xFF9DB79A)
}
