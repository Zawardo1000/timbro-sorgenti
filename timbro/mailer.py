"""Mail di contestazione: testo standard e invio via SMTP (es. Gmail con una password per le app).

Solo libreria standard. L'invio lo fa il worker, mai la richiesta HTTP: un server di posta lento o giù non blocca
l'app, e un errore temporaneo si ritenta (come le marche: dopo un'ora al massimo, per sempre). È definitivo solo un
rifiuto 5xx del messaggio o di tutti i destinatari.

Il testo è lo stesso che l'app mostra in anteprima (android/core, Contestazione): se cambia qui, va cambiato là.
"""

import re
import smtplib
import ssl
from dataclasses import dataclass
from datetime import date, datetime
from email.message import EmailMessage
from email.utils import formatdate, make_msgid, parseaddr

from timbro.config import Settings

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")  # come l'app: semplice e prevedibile
MAX_RECIPIENTS = 10
GIORNI = ["lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica"]
MESI = ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre",
        "novembre", "dicembre"]


def valid_email(address: str) -> bool:
    return len(address) <= 254 and bool(EMAIL_RE.match(address))


def configured(settings: Settings) -> bool:
    return bool(settings.smtp_host and settings.smtp_user and settings.smtp_password.get_secret_value())


def sender(settings: Settings) -> str:
    return settings.smtp_from or settings.smtp_user


def italian_day(d: date) -> str:
    return f"{GIORNI[d.weekday()]} {d.day} {MESI[d.month - 1]} {d.year}"


@dataclass
class Mail:
    subject: str
    body: str


def compose(full_name: str, employee_number: str, day: date, punch: tuple[str, datetime] | None,
            punches: list[tuple[str, datetime]], hours, url: str, valid_until: date, note: str | None) -> Mail:
    """Testo standard della contestazione (oggetto e corpo). punch = (tipo, ora) della timbratura contestata, None
    per tutta la giornata; hours = esito di orario.compute con l'orario dell'utente, None se non c'è un orario."""
    from timbro import orario

    first = (f"{full_name}, matricola {employee_number}, segnala un disallineamento tra i dati di presenza registrati "
             f"dall'ente per {italian_day(day)} e quanto effettivamente avvenuto")
    if punch is not None:
        which = "l'entrata" if punch[0] == "in" else "l'uscita"
        first += f", in particolare per {which} delle {punch[1]:%H:%M}"
    listed = ", ".join(f"{'entrata' if k == 'in' else 'uscita'} {t:%H:%M}" for k, t in punches)
    second = f"Le timbrature della giornata sono: {listed}."
    if hours is not None:
        second += (f" Secondo l'articolazione oraria in vigore risultano {orario.duration(hours.worked_min)} di lavoro "
                   f"valido, a fronte di {orario.duration(hours.due_min or 0)} dovute")
        second += f" (saldo {orario.balance(hours.balance_min)})." if hours.balance_min is not None else "."
    items = ["i video", "le firme", "le marche temporali"] + (["il calcolo delle ore"] if hours is not None else [])
    contents = ", ".join(items) + " e le istruzioni per la verifica indipendente"
    paragraphs = [
        "Gentile ufficio,",
        first + ".",
        second,
        "Ogni timbratura della giornata è documentata da una ripresa del terminale di rilevazione, firmata dal "
        "telefono e marcata temporalmente da prestatori qualificati eIDAS. Al link seguente, valido fino al "
        f"{valid_until:%d/%m/%Y}, si trovano {contents}:",
        url,
    ]
    if note:
        paragraphs.append(f"Nota: {note}")
    paragraphs += [
        "Si chiede di verificare e regolarizzare la posizione.",
        f"Cordiali saluti,\n{full_name} (matricola {employee_number})",
    ]
    return Mail(
        subject=f"Timbrature del {day:%d/%m/%Y}: segnalazione di disallineamento - {full_name}, matricola {employee_number}",
        body="\n\n".join(paragraphs) + "\n",
    )


class PermanentError(Exception):
    """Rifiuto definitivo (5xx): ritentare non serve."""


def build_message(settings: Settings, recipients: list[str], mail: Mail, reply_to: str | None = None) -> EmailMessage:
    """reply_to = indirizzo personale di chi segnala: le risposte dell'ente vanno a lui, e ne riceve copia (Cc). Il
    mittente resta la casella SMTP del server, che può essere una casella di servizio condivisa fra gli utenti."""
    msg = EmailMessage()
    msg["From"] = sender(settings)
    msg["To"] = ", ".join(recipients)
    if reply_to:
        msg["Reply-To"] = reply_to
        if reply_to not in recipients:
            msg["Cc"] = reply_to
    msg["Subject"] = mail.subject
    msg["Date"] = formatdate(localtime=True)
    domain = parseaddr(sender(settings))[1].rpartition("@")[2] or None
    msg["Message-ID"] = make_msgid(domain=domain)
    msg.set_content(mail.body)
    return msg


def send(settings: Settings, msg: EmailMessage) -> dict[str, tuple[int, bytes]]:
    """Invia; restituisce i destinatari rifiutati (se almeno uno è stato accettato). Errori temporanei come
    eccezioni normali, rifiuti definitivi come PermanentError."""
    host, port, timeout = settings.smtp_host, settings.smtp_port, settings.smtp_timeout_s
    context = ssl.create_default_context()
    try:
        if settings.smtp_security == "ssl":
            smtp = smtplib.SMTP_SSL(host, port, timeout=timeout, context=context)
        else:
            smtp = smtplib.SMTP(host, port, timeout=timeout)
        with smtp:
            if settings.smtp_security == "starttls":
                smtp.starttls(context=context)
            smtp.login(settings.smtp_user, settings.smtp_password.get_secret_value())
            return smtp.send_message(msg)
    except smtplib.SMTPRecipientsRefused as e:
        if all(code >= 500 for code, _ in e.recipients.values()):
            raise PermanentError(f"destinatari rifiutati: {_codes(e.recipients)}") from e
        raise
    except (smtplib.SMTPSenderRefused, smtplib.SMTPDataError) as e:
        if e.smtp_code >= 500:
            raise PermanentError(f"messaggio rifiutato: {e.smtp_code} {_text(e.smtp_error)}") from e
        raise


def _codes(refused: dict) -> str:
    return ", ".join(f"{addr} ({code})" for addr, (code, _) in refused.items())


def _text(value) -> str:
    return value.decode("utf-8", "replace") if isinstance(value, bytes) else str(value)
