package it.zawardo.timbro

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import it.zawardo.timbro.ui.Radice
import it.zawardo.timbro.ui.theme.TemaTimbro

/**
 * FragmentActivity e non ComponentActivity: BiometricPrompt la richiede. L'impronta serve solo alle operazioni di
 * gestione (link, flag, cancellazioni), firmate con la chiave admin del Keystore; l'app si apre senza, per timbrare
 * in fretta (scelta del 05/11/2026, docs/PROGETTO.md §5.2).
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // L'interfaccia è sempre scura (stile C): icone chiare sulle barre, anche col tema di sistema chiaro. Sopra il
        // blocco colorato di Timbra App le rende scure finché la schermata è aperta.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // nell'elenco delle app recenti l'anteprima non mostra storico e video (gli screenshot restano possibili).
        // Esiste solo da Android 13: sotto resta visibile (docs/PROGETTO.md §20)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        setContent { TemaTimbro { Radice() } }
    }
}
