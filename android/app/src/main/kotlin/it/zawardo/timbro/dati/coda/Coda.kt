package it.zawardo.timbro.dati.coda

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import it.zawardo.timbro.core.StatoInvio
import kotlinx.coroutines.flow.Flow

/**
 * Una timbratura del telefono, dalla registrazione all'arrivo al server (docs/PROGETTO.md §20, §22). Si salvano i
 * **byte esatti** del manifest, la firma e la catena di attestazione così come sono stati prodotti: si reinviano
 * identici, anche dopo un aggiornamento dell'app (riserializzare cambierebbe i byte: 409 per sempre). Il worker
 * (InvioWorker) manda manifest e video e poi legge dal server lo stato delle prove (marca, attestazione, Bitcoin).
 */
@Entity(tableName = "coda", indices = [Index(value = ["client_id"], unique = true), Index(value = ["stato", "prossimo_tentativo"])])
data class TimbraturaInCoda(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "client_id") val clientId: String,
    val kind: String,
    /** Byte esatti del manifest firmato (JSON). */
    val manifest: ByteArray,
    /** Firma ECDSA DER dei byte del manifest. */
    val firma: ByteArray,
    /** Catena di attestazione della chiave usa e getta (DER base64, dalla foglia alla radice), come JSON; null se assente. */
    val attestazione: String?,
    /** Video nella cartella privata dell'app; si cancella quando il server lo ha ricevuto. */
    @ColumnInfo(name = "video") val percorsoVideo: String,
    @ColumnInfo(name = "video_sha256") val videoSha256: String,
    /** Ora della timbratura (inizio della registrazione, la stessa del device_time del manifest), epoch ms. */
    @ColumnInfo(name = "creata_il") val creataIl: Long,
    val stato: StatoInvio = StatoInvio.DA_INVIARE,
    /** Id della timbratura sul server, dopo che ha accettato il manifest. */
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    val tentativi: Int = 0,
    @ColumnInfo(name = "prossimo_tentativo") val prossimoTentativo: Long = 0,
    @ColumnInfo(name = "ultimo_errore") val ultimoErrore: String? = null,
    // --- stato delle prove letto dal server (GET /api/v1/punches/{id}) -----------------------------------------------
    /** Almeno una marca qualificata completata sul video. */
    @ColumnInfo(name = "marcata", defaultValue = "0") val marcata: Boolean = false,
    @ColumnInfo(name = "video_ricevuto", defaultValue = "0") val videoRicevuto: Boolean = false,
    /** Esito dell'attestazione hardware secondo il server; null finché non si sa (o se non c'era). */
    @ColumnInfo(name = "integro") val integro: Boolean? = null,
    @ColumnInfo(name = "problemi_attestazione") val problemiAttestazione: String? = null,
    /** Prova OpenTimestamps del video confermata su Bitcoin. */
    @ColumnInfo(name = "bitcoin", defaultValue = "0") val bitcoin: Boolean = false,
    @ColumnInfo(name = "stato_letto_il", defaultValue = "0") val statoLettoIl: Long = 0,
) {
    // ByteArray: equals/hashCode per contenuto (Room confronta le righe solo per chiave, ma le data class li usano)
    override fun equals(other: Any?) = other is TimbraturaInCoda && other.id == id && other.clientId == clientId &&
        other.manifest.contentEquals(manifest) && other.firma.contentEquals(firma) && other.stato == stato &&
        other.serverId == serverId && other.tentativi == tentativi && other.marcata == marcata &&
        other.videoRicevuto == videoRicevuto && other.integro == integro && other.bitcoin == bitcoin &&
        other.ultimoErrore == ultimoErrore
    override fun hashCode() = 31 * clientId.hashCode() + manifest.contentHashCode()

    /** Ancora qualcosa da sapere dal server (marca, attestazione, video, Bitcoin). */
    val provePendenti: Boolean get() = serverId != null && (!marcata || !videoRicevuto || !bitcoin || (attestazione != null && integro == null))
}

/**
 * L'ultimo storico letto dal server (`GET /api/v1/history`), per aprire l'app senza rete. Una riga per timbratura; si
 * sostituisce per intervalli di giorni a ogni lettura (StoricoDao.sostituisci). Solo i campi dello storico: posizione,
 * reti e note restano sul server, dietro l'impronta.
 */
@Entity(tableName = "storico", indices = [Index(value = ["giorno"]), Index(value = ["client_id"])])
data class VoceInCache(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "client_id") val clientId: String,
    val kind: String,
    /** Ora della timbratura come la manda il server (ISO 8601 con fuso). */
    val time: String,
    @ColumnInfo(name = "ricevuta_il") val ricevutaIl: String,
    /** Giorno in ora italiana (yyyy-MM-dd), come lo raggruppa il server. */
    val giorno: String,
    val video: String,
    val marcata: Boolean,
    @ColumnInfo(name = "attestazione_ok") val attestazioneOk: Boolean?,
    val contestata: Boolean,
    @ColumnInfo(name = "da_tenere") val daTenere: Boolean,
)

@Dao
interface StoricoDao {
    @Query("SELECT * FROM storico ORDER BY time DESC")
    suspend fun tutte(): List<VoceInCache>

    @Query("DELETE FROM storico WHERE giorno >= :dal AND giorno <= :fino")
    suspend fun cancellaGiorni(dal: String, fino: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun aggiungi(voci: List<VoceInCache>)

    /** I giorni letti dal server sostituiscono quelli in cache (una timbratura sparita dal server sparisce anche qui). */
    @androidx.room.Transaction
    suspend fun sostituisci(dal: String, fino: String, voci: List<VoceInCache>) {
        cancellaGiorni(dal, fino)
        aggiungi(voci)
    }

    @Query("DELETE FROM storico")
    suspend fun svuota()
}

class Convertitori {
    @TypeConverter fun daStato(s: StatoInvio): String = s.name
    @TypeConverter fun aStato(s: String): StatoInvio = StatoInvio.valueOf(s)
}

@Dao
interface CodaDao {
    /** Una timbratura con lo stesso client_id c'è già: si tiene quella (i byte firmati non cambiano). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun aggiungi(t: TimbraturaInCoda): Long

    @Query("SELECT * FROM coda WHERE client_id = :clientId")
    suspend fun perClientId(clientId: String): TimbraturaInCoda?

    @Query("SELECT * FROM coda WHERE id = :id")
    suspend fun perId(id: Long): TimbraturaInCoda?

    /** Da inviare adesso, dalla più vecchia. */
    @Query("SELECT * FROM coda WHERE stato IN ('DA_INVIARE', 'VIDEO_DA_INVIARE') AND prossimo_tentativo <= :adesso ORDER BY creata_il")
    suspend fun daInviare(adesso: Long): List<TimbraturaInCoda>

    /** Il prossimo tentativo programmato (per ripianificare il worker), null se non c'è niente in coda. */
    @Query("SELECT MIN(prossimo_tentativo) FROM coda WHERE stato IN ('DA_INVIARE', 'VIDEO_DA_INVIARE')")
    suspend fun prossimoTentativo(): Long?

    @Query("UPDATE coda SET stato = :stato, server_id = :serverId, tentativi = 0, prossimo_tentativo = 0, ultimo_errore = NULL WHERE id = :id")
    suspend fun avanza(id: Long, stato: StatoInvio, serverId: Long?)

    @Query("UPDATE coda SET tentativi = tentativi + 1, prossimo_tentativo = :prossimo, ultimo_errore = :errore WHERE id = :id")
    suspend fun ritenta(id: Long, prossimo: Long, errore: String)

    @Query("UPDATE coda SET stato = 'RIFIUTATA', ultimo_errore = :errore WHERE id = :id")
    suspend fun rifiuta(id: Long, errore: String)

    @Query(
        "UPDATE coda SET marcata = :marcata, video_ricevuto = :videoRicevuto, integro = :integro, " +
            "problemi_attestazione = :problemi, bitcoin = :bitcoin, stato_letto_il = :adesso WHERE id = :id",
    )
    suspend fun aggiornaProve(id: Long, marcata: Boolean, videoRicevuto: Boolean, integro: Boolean?, problemi: String?, bitcoin: Boolean, adesso: Long)

    /** Arrivate al server ma con prove ancora da completare, recenti (dopo una settimana non si chiede più). */
    @Query(
        "SELECT * FROM coda WHERE server_id IS NOT NULL AND creata_il >= :dal AND " +
            "(marcata = 0 OR video_ricevuto = 0 OR bitcoin = 0 OR (attestazione IS NOT NULL AND integro IS NULL)) " +
            "ORDER BY creata_il DESC LIMIT :quante",
    )
    suspend fun conProvePendenti(dal: Long, quante: Int): List<TimbraturaInCoda>

    @Query("SELECT COUNT(*) FROM coda WHERE stato IN ('DA_INVIARE', 'VIDEO_DA_INVIARE')")
    suspend fun inAttesa(): Int

    /** Timbrature che il server non ha più (cancellate lì): la coda le dimentica (StatoApp.carica). */
    @Query("DELETE FROM coda WHERE id IN (:ids)")
    suspend fun elimina(ids: List<Long>)

    @Query("SELECT * FROM coda ORDER BY creata_il DESC")
    suspend fun tutte(): List<TimbraturaInCoda>

    /** Per l'interfaccia: si aggiorna da sola a ogni cambio della coda. */
    @Query("SELECT * FROM coda ORDER BY creata_il DESC")
    fun osserva(): Flow<List<TimbraturaInCoda>>
}

@Database(
    entities = [TimbraturaInCoda::class, VoceInCache::class],
    version = 3,
    exportSchema = true,
    // 2: stato delle prove letto dal server (colonne aggiunte, con valori predefiniti); 3: cache dello storico
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Convertitori::class)
abstract class CodaDb : RoomDatabase() {
    abstract fun coda(): CodaDao
    abstract fun storico(): StoricoDao

    companion object {
        fun apri(contesto: Context): CodaDb = Room.databaseBuilder(contesto, CodaDb::class.java, "coda.db").build()
    }
}
