package it.zawardo.timbro.sveglia

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import it.zawardo.timbro.MainActivity
import it.zawardo.timbro.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Sveglia per il rientro dalla pausa: suona alla fine della pausa minima (uscita + 15 minuti, core `Sveglia`).
 *
 * `setAlarmClock`: esatta anche in Doze e mostrata dal sistema come sveglia (icona nella barra di stato). Se il
 * permesso delle sveglie esatte manca (Android 12, revocato dall'utente) ripiega su una sveglia approssimativa e
 * lo dice. All'ora giusta [SvegliaReceiver] pubblica una notifica sul canale delle sveglie (suono della sveglia,
 * ripetuto finché non la si tocca o scarta). L'ora programmata resta nelle preferenze: la schermata Pausa la
 * ritrova anche dopo la morte del processo. Un riavvio del telefono durante la pausa la cancella (non si
 * riprogramma: dura al massimo 15 minuti).
 */
class SvegliaRientro(context: Context) {
    private val ctx = context.applicationContext
    private val allarmi = ctx.getSystemService(AlarmManager::class.java)
    private val preferenze = ctx.getSharedPreferences("sveglia", Context.MODE_PRIVATE)

    /** Ora della sveglia del rientro, se è programmata e non ancora suonata. */
    fun programmata(ora: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val millis = preferenze.getLong(CHIAVE, 0L).takeIf { it > 0 } ?: return null
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).takeIf { it.isAfter(ora) }
    }

    /** Programma la sveglia; false se il sistema la concede solo approssimativa. */
    fun programma(alle: LocalDateTime): Boolean {
        val millis = alle.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val esatta = imposta(millis, RICHIESTA_RIENTRO)
        preferenze.edit { putLong(CHIAVE, millis) }
        return esatta
    }

    fun annulla() {
        allarmi.cancel(azione(RICHIESTA_RIENTRO))
        preferenze.edit { remove(CHIAVE) }
        NotificationManagerCompat.from(ctx).cancel(NOTIFICA)
    }

    /** Spegne la notifica che sta suonando (al rientro, o aprendo l'app). */
    fun zittisci() = NotificationManagerCompat.from(ctx).cancel(NOTIFICA)

    /** Prova dalle Impostazioni: suona fra pochi secondi, senza toccare la sveglia del rientro. */
    fun prova(secondi: Long = 10): Boolean = imposta(System.currentTimeMillis() + secondi * 1000, RICHIESTA_PROVA, prova = true)

    fun puoNotificare(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun imposta(millis: Long, richiesta: Int, prova: Boolean = false): Boolean {
        val esatta = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || allarmi.canScheduleExactAlarms()
        if (esatta) {
            allarmi.setAlarmClock(AlarmManager.AlarmClockInfo(millis, apriApp()), azione(richiesta, prova))
        } else {
            Log.w(TAG, "sveglie esatte non permesse: sveglia approssimativa")
            allarmi.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, azione(richiesta, prova))
        }
        return esatta
    }

    /** La prova suona come quella vera ma non tocca l'ora della sveglia del rientro. */
    internal fun suona(prova: Boolean) {
        if (!prova) preferenze.edit { remove(CHIAVE) }
        val gestore = ctx.getSystemService(NotificationManager::class.java)
        gestore.createNotificationChannel(
            NotificationChannel(CANALE, "Sveglia per il rientro", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Suona alla fine della pausa minima di 15 minuti"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600)
            },
        )
        val notifica = NotificationCompat.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_sveglia)
            .setColor(0xFF3DFFB0.toInt())
            .setContentTitle(if (prova) "Prova della sveglia" else "Rientro")
            .setContentText(if (prova) "Così suonerà alla fine della pausa minima" else "Pausa minima finita: puoi timbrare il rientro")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(apriApp())
            .setAutoCancel(true)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }  // suona finché non la si tocca o scarta
        if (!puoNotificare()) {
            Log.w(TAG, "notifiche non permesse: la sveglia non può suonare")
            return
        }
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICA, notifica)
        } catch (e: SecurityException) {
            Log.w(TAG, "notifica della sveglia rifiutata", e)
        }
    }

    private fun azione(richiesta: Int, prova: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        ctx, richiesta, Intent(ctx, SvegliaReceiver::class.java).putExtra(EXTRA_PROVA, prova),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun apriApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val TAG = "SvegliaRientro"
        const val CANALE = "sveglia-rientro"
        const val CHIAVE = "alle"
        const val NOTIFICA = 15
        const val RICHIESTA_RIENTRO = 1
        const val RICHIESTA_PROVA = 2
        const val EXTRA_PROVA = "prova"
    }
}

/** Riceve la sveglia dall'AlarmManager e la fa suonare. */
class SvegliaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        SvegliaRientro(context).suona(prova = intent.getBooleanExtra("prova", false))
}
