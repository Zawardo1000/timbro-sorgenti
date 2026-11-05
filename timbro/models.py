from datetime import date, datetime, time

from sqlalchemy import (
    Boolean,
    Date,
    ForeignKey,
    Integer,
    LargeBinary,
    SmallInteger,
    String,
    Text,
    Time,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from timbro.db import Base, UTCDateTime, utcnow


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True)
    password_hash: Mapped[str | None] = mapped_column(String(255))
    # "user": gestisce solo le proprie timbrature e il proprio profilo; "admin": anche articolazioni orarie e
    # assegnazioni di tutti (python -m timbro set-role)
    role: Mapped[str] = mapped_column(String(16), default="user", server_default="user")
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)


class PairingCode(Base):
    """Codice monouso per associare un telefono a un utente."""

    __tablename__ = "pairing_codes"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    used_at: Mapped[datetime | None] = mapped_column(UTCDateTime)


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    name: Mapped[str] = mapped_column(String(100))
    public_key_pem: Mapped[str] = mapped_column(Text)
    # catena di Key Attestation (PEM concatenati) così come inviata dal telefono
    attestation_chain_pem: Mapped[str | None] = mapped_column(Text)
    # chiave P-256 utilizzabile solo dopo autenticazione biometrica sul telefono: firma le
    # operazioni di gestione (link di condivisione, flag, cancellazione video)
    admin_public_key_pem: Mapped[str | None] = mapped_column(Text)
    api_token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    # certificato client mTLS rilasciato all'associazione (/api/v1/enroll): impronta SHA-256 del DER, per riferimento
    client_cert_sha256: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    revoked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)


class Punch(Base):
    """Una timbratura documentata. Il manifest firmato dal telefono è conservato byte per byte."""

    __tablename__ = "punches"
    __table_args__ = (UniqueConstraint("device_id", "client_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"))
    client_id: Mapped[str] = mapped_column(String(36))
    kind: Mapped[str] = mapped_column(String(3))  # in | out
    sha256: Mapped[str] = mapped_column(String(64), index=True)
    size: Mapped[int] = mapped_column(Integer)
    duration_ms: Mapped[int] = mapped_column(Integer)
    device_time: Mapped[str] = mapped_column(String(40))
    location_json: Mapped[str | None] = mapped_column(Text)
    manifest_raw: Mapped[bytes] = mapped_column(LargeBinary)
    device_signature: Mapped[bytes] = mapped_column(LargeBinary)
    server_received_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    # Catena dei record: prev unico impedisce biforcazioni, chain = sha256(prev || sha256(manifest))
    prev_chain_hash: Mapped[str] = mapped_column(String(64), unique=True)
    chain_hash: Mapped[str] = mapped_column(String(64), unique=True)

    video_path: Mapped[str | None] = mapped_column(String(255))
    video_received_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    video_deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    video_deleted_reason: Mapped[str | None] = mapped_column(String(16))  # retention | manual

    # attestazione hardware del telefono (catena PEM dalla foglia alla radice, così com'è nel pacchetto di prova e
    # coperta dalle marche "attest") e suo esito (attestation.Result in JSON)
    attestation_pem: Mapped[str | None] = mapped_column(Text)
    attestation_json: Mapped[str | None] = mapped_column(Text)
    # commit del server che ha ricevuto la timbratura (gitdeploy, .gitdeploy-commit): i suoi sorgenti sono nella release
    # pubblica timbro-<7 cifre> (docs/PROGETTO.md §25); null sul PC e per le timbrature di prima
    server_version: Mapped[str | None] = mapped_column(String(40))

    keep: Mapped[bool] = mapped_column(Boolean, default=False)
    contested: Mapped[bool] = mapped_column(Boolean, default=False)
    justification_ref: Mapped[str | None] = mapped_column(String(255))
    notes: Mapped[str | None] = mapped_column(Text)

    stamps: Mapped[list["Stamp"]] = relationship(back_populates="punch", order_by="Stamp.id")


class Stamp(Base):
    """Una marca temporale (RFC 3161 o OpenTimestamps) su un'impronta.

    target:
      video  -> sha256 del file video (verificabile con `openssl ts -verify -data video.mp4`)
      record -> chain_hash della timbratura (copre manifest, firma, chiave, ora di ricezione)
      chain  -> testa della catena a fine giornata (chain_day)
      attest -> sha256 della catena di attestazione in PEM (prova che esisteva entro la marca)
    """

    __tablename__ = "stamps"
    __table_args__ = (
        UniqueConstraint("punch_id", "target", "provider"),
        UniqueConstraint("chain_day", "target", "provider"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    target: Mapped[str] = mapped_column(String(8))
    punch_id: Mapped[int | None] = mapped_column(ForeignKey("punches.id"))
    chain_day: Mapped[date | None] = mapped_column(Date)
    provider: Mapped[str] = mapped_column(String(32))
    kind: Mapped[str] = mapped_column(String(8))  # rfc3161 | ots
    qualified: Mapped[bool] = mapped_column(Boolean, default=False)
    digest: Mapped[str] = mapped_column(String(64))  # impronta marcata (hex)

    # pending -> complete | failed (rfc3161); pending -> upgrading -> complete (ots)
    status: Mapped[str] = mapped_column(String(16), default="pending", index=True)
    request: Mapped[bytes | None] = mapped_column(LargeBinary)
    token: Mapped[bytes | None] = mapped_column(LargeBinary)
    gen_time: Mapped[datetime | None] = mapped_column(UTCDateTime)
    accuracy_ms: Mapped[int | None] = mapped_column(Integer)
    info_json: Mapped[str | None] = mapped_column(Text)
    # risposte OCSP e CRL raccolte alla verifica (JSON: {"ocsp": [b64 DER], "crl": [b64 DER]}),
    # per dimostrare in futuro che il certificato della TSA non era revocato
    revinfo_json: Mapped[str | None] = mapped_column(Text)

    attempts: Mapped[int] = mapped_column(Integer, default=0)
    next_attempt_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    last_error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    completed_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    punch: Mapped[Punch | None] = relationship(back_populates="stamps")


class AdminNonce(Base):
    """Nonce delle richieste admin già viste (anti-replay), tenuti fino alla scadenza della finestra."""

    __tablename__ = "admin_nonces"

    nonce: Mapped[str] = mapped_column(String(64), primary_key=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"))
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime, index=True)


class ShareLink(Base):
    """Link di sola lettura per terzi (avvocato, giudice...), con scadenza e revocabile."""

    __tablename__ = "share_links"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    label: Mapped[str] = mapped_column(String(200))
    punch_ids_json: Mapped[str] = mapped_column(Text)
    # consente di scaricare il pacchetto di prova (zip); la visione del video nella pagina
    # resta possibile in ogni caso, e chi guarda può sempre registrarla
    allow_evidence_download: Mapped[bool] = mapped_column(Boolean, default=True)
    created_by_device: Mapped[str] = mapped_column(ForeignKey("devices.id"))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    # nota della contestazione, scritta dall'utente per chi riceve il link (mostrata nella pagina)
    note: Mapped[str | None] = mapped_column(Text)
    # giorno segnalato, se il link nasce da una contestazione (di una timbratura o di tutta la giornata)
    contest_day: Mapped[date | None] = mapped_column(Date)

    accesses: Mapped[list["ShareAccess"]] = relationship(back_populates="share", order_by="ShareAccess.id")


class ShareAccess(Base):
    __tablename__ = "share_accesses"

    id: Mapped[int] = mapped_column(primary_key=True)
    share_id: Mapped[int] = mapped_column(ForeignKey("share_links.id"), index=True)
    at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    ip: Mapped[str | None] = mapped_column(String(64))
    user_agent: Mapped[str | None] = mapped_column(String(300))
    what: Mapped[str] = mapped_column(String(100))  # page | video:<id> | evidence:<id>

    share: Mapped[ShareLink] = relationship(back_populates="accesses")


class ContestNotice(Base):
    """Mail di contestazione di una timbratura: destinatari, testo esatto inviato e stato dell'invio (worker)."""

    __tablename__ = "contest_notices"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    # la timbratura contestata, o None se si segnala tutta la giornata (es. calcolo delle ore sbagliato dall'ente)
    punch_id: Mapped[int | None] = mapped_column(ForeignKey("punches.id"), index=True)
    day: Mapped[date] = mapped_column(Date)
    share_id: Mapped[int] = mapped_column(ForeignKey("share_links.id"))
    recipients_json: Mapped[str] = mapped_column(Text)
    note: Mapped[str | None] = mapped_column(Text)
    subject: Mapped[str] = mapped_column(String(300))
    body: Mapped[str] = mapped_column(Text)  # il testo esatto della mail, con il link
    reply_to: Mapped[str | None] = mapped_column(String(254))  # indirizzo personale: Reply-To e Cc
    # pending (da inviare) | retrying (errore temporaneo) | sent | failed (rifiuto definitivo)
    status: Mapped[str] = mapped_column(String(16), default="pending", index=True)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    next_attempt_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    sent_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    message_id: Mapped[str | None] = mapped_column(String(300))
    error: Mapped[str | None] = mapped_column(Text)


class Profile(Base):
    """Dati della persona per le contestazioni: nome e matricola (uno per utente)."""

    __tablename__ = "profiles"

    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)
    full_name: Mapped[str | None] = mapped_column(String(100))
    employee_number: Mapped[str | None] = mapped_column(String(40))
    # indirizzo personale: Reply-To e copia (Cc) delle mail di contestazione, che partono dalla casella del server
    email: Mapped[str | None] = mapped_column(String(254))
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, onupdate=utcnow)

    recipients: Mapped[list["ProfileRecipient"]] = relationship(
        order_by="ProfileRecipient.position", cascade="all, delete-orphan"
    )
    assignments: Mapped[list["ProfileSchedule"]] = relationship(
        order_by="ProfileSchedule.valid_from", cascade="all, delete-orphan"
    )


class ProfileRecipient(Base):
    """Destinatari delle mail di contestazione, in ordine."""

    __tablename__ = "profile_recipients"
    __table_args__ = (UniqueConstraint("user_id", "email"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("profiles.user_id"), index=True)
    email: Mapped[str] = mapped_column(String(254))
    position: Mapped[int] = mapped_column(Integer, default=0)


class Schedule(Base):
    """Articolazione oraria con un nome, condivisibile fra più profili. Le regole stanno nelle versioni datate."""

    __tablename__ = "schedules"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100), unique=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    versions: Mapped[list["ScheduleVersion"]] = relationship(
        order_by="ScheduleVersion.valid_from", cascade="all, delete-orphan"
    )


class ScheduleVersion(Base):
    """Regole di un'articolazione dal giorno valid_from (fino alla versione successiva)."""

    __tablename__ = "schedule_versions"
    __table_args__ = (UniqueConstraint("schedule_id", "valid_from"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    schedule_id: Mapped[int] = mapped_column(ForeignKey("schedules.id"), index=True)
    valid_from: Mapped[date] = mapped_column(Date)
    entry_from: Mapped[time] = mapped_column(Time)
    entry_to: Mapped[time] = mapped_column(Time)
    pause_from: Mapped[time] = mapped_column(Time)
    pause_to: Mapped[time] = mapped_column(Time)
    pause_counted_min: Mapped[int] = mapped_column(Integer)
    pause_terminal_min: Mapped[int] = mapped_column(Integer)
    no_pause_after_min: Mapped[int] = mapped_column(Integer)
    no_pause_deduct_min: Mapped[int] = mapped_column(Integer)

    days: Mapped[list["ScheduleDay"]] = relationship(order_by="ScheduleDay.weekday", cascade="all, delete-orphan")


class ScheduleDay(Base):
    """Ore dovute di un giorno della settimana (1 = lunedì) in una versione; un giorno assente vale 0."""

    __tablename__ = "schedule_days"

    version_id: Mapped[int] = mapped_column(ForeignKey("schedule_versions.id"), primary_key=True)
    weekday: Mapped[int] = mapped_column(SmallInteger, primary_key=True)
    due_min: Mapped[int] = mapped_column(Integer)
    pause: Mapped[bool] = mapped_column(Boolean, default=True)


class ProfileSchedule(Base):
    """Articolazione assegnata a un profilo dal giorno valid_from (fino all'assegnazione successiva)."""

    __tablename__ = "profile_schedules"
    __table_args__ = (UniqueConstraint("user_id", "valid_from"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("profiles.user_id"), index=True)
    schedule_id: Mapped[int] = mapped_column(ForeignKey("schedules.id"), index=True)
    valid_from: Mapped[date] = mapped_column(Date)

    schedule: Mapped[Schedule] = relationship()


class Setting(Base):
    __tablename__ = "settings"

    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    value: Mapped[str] = mapped_column(Text)
