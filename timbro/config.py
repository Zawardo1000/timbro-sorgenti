"""Tutta la configurazione, dalle variabili d'ambiente (sul PC anche da .env).

I nomi delle variabili sono quelli dei campi in maiuscolo (DATABASE_URL, DATA_DIR, ...). Sul server percorsi e
porta (WEB_HOST, WEB_PORT, LOG_DIR, DATA_DIR) li fissa il compose; segreti e impostazioni stanno nel .env dell'app.
Gli altri moduli leggono da qui, mai da os.getenv.
"""

from functools import cache
from pathlib import Path
from typing import Literal

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

APP_NAME = "timbro"
BASE_DIR = Path(__file__).resolve().parent.parent  # radice del repo = /app nel container: solo lettura


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # obbligatoria (vedi .env.example): postgresql+psycopg://utente:password@host:5432/db
    database_url: str = ""
    web_host: str = "127.0.0.1"  # sul server 0.0.0.0, solo dentro il container
    web_port: int = 8012
    log_dir: Path = BASE_DIR / "logs"
    data_dir: Path = BASE_DIR / "local-data"  # video, cache dei pacchetti, Trusted List archiviate

    # Provider RFC 3161 abilitati, nell'ordine di priorità (vedi stamping/tsa.py).
    # Dal 05/11/2026 solo le due qualificate: FreeTSA (servizio di una persona, lento, errori interni) e DigiCert (non
    # qualificato) non aggiungevano valore legale; l'indipendenza da tutti i prestatori la dà OpenTimestamps. Le loro
    # definizioni restano in stamping/tsa.py per verificare le marche già fatte.
    tsa_providers: list[str] = ["accv", "sectigo-q"]
    # marche sul record della timbratura (chain_hash: manifest con entrata/uscita, ora e posizione, firma, chiave, ora
    # di ricezione) e sulla sua attestazione hardware: le due qualificate, come il video (prima del 05/11/2026 solo
    # DigiCert, e il record riceveva una marca qualificata solo con quella giornaliera della catena)
    record_tsa_providers: list[str] = ["accv", "sectigo-q"]
    # testa della catena del giorno: la prima qualificata disponibile di tsa_providers, più OTS.
    # Si aspetta un po' dopo mezzanotte, così le timbrature a cavallo sono già registrate.
    chain_stamp_grace_minutes: int = 10
    chain_stamp_catchup_days: int = 31
    # scarto massimo ammesso fra genTime di una marca nuova e l'orologio del server
    max_gen_time_skew_s: int = 120
    # limiti sul corpo delle richieste (prima di leggere/validare alcunché)
    max_json_body_bytes: int = 64 * 1024
    ots_enabled: bool = True
    ots_calendars: list[str] = [
        "https://a.pool.opentimestamps.org",
        "https://b.pool.opentimestamps.org",
        "https://a.pool.eternitywall.com",
        "https://ots.btc.catallaxy.com",
    ]
    # Solo da questi calendar si accettano gli upgrade delle prove OTS.
    ots_upgrade_whitelist: list[str] = [
        "https://*.calendar.opentimestamps.org",
        "https://*.calendar.eternitywall.com",
        "https://*.calendar.catallaxy.com",
    ]
    bitcoin_explorer: str = "https://blockstream.info/api"

    max_video_bytes: int = 50 * 1024 * 1024

    # Attestazione hardware di ogni timbratura (timbro/attestation.py): radici di Google fissate nel repo
    attestation_roots_dir: Path = BASE_DIR / "timbro" / "attestation_roots"
    attestation_required: bool = True  # timbratura senza attestazione valida: si accetta, ma parte l'avviso
    attestation_package: str = ""  # nome del pacchetto dell'app Android; vuoto = non controllato
    attestation_signing_cert_sha256: list[str] = []  # impronte del certificato di firma dell'APK; vuoto = non controllato
    attestation_status_url: str = "https://android.googleapis.com/attestation/status"
    attestation_revocation_check_h: float = 24
    # indirizzo pubblico della pagina dei link di condivisione
    public_base_url: str = "http://127.0.0.1:8012"
    # repository pubblico dei sorgenti (app e server), con una release per ogni versione in produzione (§25)
    public_sources_url: str = "https://github.com/Zawardo1000/timbro-sorgenti"
    # CA intermedia che firma i certificati client (mTLS) dei telefoni all'associazione (timbro/enroll.py): file
    # montati in sola lettura; senza, /api/v1/enroll risponde 503
    client_ca_cert_file: Path | None = None
    client_ca_key_file: Path | None = None
    client_cert_days: int = 730
    # Invio delle mail di contestazione (timbro/mailer.py), per esempio Gmail con una password per le app:
    # SMTP_HOST=smtp.gmail.com, SMTP_PORT=465, SMTP_SECURITY=ssl. Senza SMTP_HOST le contestazioni creano solo il link.
    smtp_host: str = ""
    smtp_port: int = 465
    smtp_security: Literal["ssl", "starttls"] = "ssl"
    smtp_user: str = ""
    smtp_password: SecretStr = SecretStr("")
    smtp_from: str = ""  # mittente, es. "Mario Rossi <mario.rossi@gmail.com>"; vuoto = SMTP_USER
    smtp_timeout_s: float = 30
    alert_mail_after_min: int = 15  # mail di contestazione non ancora partita dopo N minuti (o rifiutata)
    # conservazione dei video (modificabile dall'app); hash, marche e catena non si cancellano mai
    retention_days_default: int = 90
    # validità dei link di condivisione (token nell'URL): la sceglie l'app, se non la indica 15 giorni
    share_default_days: int = 15
    share_max_days: int = 60
    # richieste al minuto per singolo link pubblico (pagina, video, pacchetto)
    share_rate_limit_per_min: int = 60
    admin_clock_skew_s: int = 300
    pairing_code_minutes: int = 15

    worker_enabled: bool = True
    worker_interval_s: float = 5.0
    ots_upgrade_interval_s: float = 3600.0
    # controlli periodici del worker: Trusted List (e copia archiviata) e verifica completa dell'archivio
    periodic_checks_enabled: bool = True
    tl_check_interval_h: float = 24 * 7
    archive_check_local_hour: int = 3  # ogni giorno, ora italiana (prima del backup delle 03:30)

    # Soglie dei problemi segnalati su Telegram (GET /internal/problemi, letto da timbro-controllo sul server)
    alert_stamp_after_min: int = 30  # timbratura senza marca qualificata (video) o sul record
    alert_tsa_failed_attempts: int = 3  # ultima marca di un provider ancora pending dopo N tentativi
    alert_video_missing_after_h: int = 12  # timbratura arrivata ma video mai caricato
    alert_ots_pending_after_h: int = 72  # prova OTS non ancora confermata su Bitcoin
    alert_chain_local_hour: int = 2  # la marca della catena di ieri deve esserci entro quest'ora
    alert_chain_days: int = 7  # giorni indietro controllati per la marca della catena
    alert_worker_stale_min: int = 10  # worker senza un giro completo da più di N minuti

    @field_validator("tsa_providers", "record_tsa_providers")
    @classmethod
    def _known_providers(cls, v: list[str]) -> list[str]:
        """Un nome sbagliato nel .env deve fermare l'avvio (e quindi il deploy), non far fallire ogni timbratura."""
        from timbro.stamping.tsa import PROVIDERS

        unknown = [p for p in v if p not in PROVIDERS]
        if unknown:
            raise ValueError(f"provider TSA sconosciuti: {unknown} (ammessi: {sorted(PROVIDERS)})")
        if len(set(v)) != len(v):
            raise ValueError(f"provider TSA ripetuti: {v}")
        return v

    @model_validator(mode="after")
    def _one_qualified(self):
        from timbro.stamping.tsa import PROVIDERS

        if not any(PROVIDERS[p].qualified for p in self.tsa_providers):
            raise ValueError("tsa_providers deve contenere almeno una TSA qualificata (accv, sectigo-q)")
        return self

    @property
    def videos_dir(self) -> Path:
        return self.data_dir / "videos"


def check_required(settings: Settings) -> Settings:
    if not settings.database_url.strip():
        raise RuntimeError("Variabile d'ambiente mancante: DATABASE_URL (vedi .env.example)")
    return settings


@cache
def get_settings() -> Settings:
    return check_required(Settings())
