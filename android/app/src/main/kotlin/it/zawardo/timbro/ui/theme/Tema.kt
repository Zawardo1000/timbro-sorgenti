package it.zawardo.timbro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Sempre scuro: è il disegno dell'app, non segue il tema di sistema. */
private val schema = darkColorScheme(
    primary = Colori.Inchiostro,
    onPrimary = Colori.SuColore,
    primaryContainer = Colori.AccentoChiaro,
    onPrimaryContainer = Colori.Entrata,
    secondary = Colori.Entrata,
    onSecondary = Colori.SuColore,
    background = Colori.Fondo,
    onBackground = Colori.Inchiostro,
    surface = Colori.Superficie,
    onSurface = Colori.Inchiostro,
    surfaceVariant = Colori.Rilievo,
    onSurfaceVariant = Colori.Testo2,
    surfaceContainerHigh = Colori.Superficie,
    surfaceContainerHighest = Colori.Rilievo,
    outline = Colori.BordoControllo,
    outlineVariant = Colori.Bordo,
    error = Colori.Pericolo,
)

@Composable
fun TemaTimbro(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = schema,
        typography = Typography(
            bodyLarge = Testo.Corpo16,
            bodyMedium = Testo.Corpo14,
            bodySmall = Testo.Piccolo12,
            labelLarge = Testo.Corpo15,
            titleLarge = Testo.Titolo22,
            headlineSmall = Testo.Titolo22,
        ),
        content = content,
    )
}
