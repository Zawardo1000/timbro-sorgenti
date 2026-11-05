# Timbro: sorgenti pubblici

Timbro è un registro probatorio delle proprie timbrature: l'app Android riprende per pochi secondi il terminale
di rilevazione, firma i dati con una chiave custodita nell'hardware del telefono e li manda a un server, che li
fa datare da prestatori qualificati eIDAS e dalla blockchain Bitcoin e li collega in una catena. Chi riceve una
segnalazione apre una pagina con le prove e può scaricare un pacchetto da verificare da solo.

Questo repository contiene **tutti i sorgenti dell'app e del server**, così come sono in ogni versione, perché
chiunque possa controllare che cosa fanno e da dove vengono. Non contiene dati: niente indirizzi del server,
chiavi, persone o timbrature.

## Da dove vengono i commit

Ogni commit di questo repository corrisponde a una versione del repository di sviluppo (privato) che ha superato
i controlli automatici, ed è pubblicato **prima** che quella versione vada sul server. Il file `VERSIONE` dice
quale: commit, numero (è il `versionCode` dell'app) e data. Il tag `timbro-<7 cifre>` porta lo stesso commit.

Dal repository di sviluppo restano fuori solo le note operative, che l'app e il server non usano: le istruzioni
interne per lo sviluppo e per il server (`CLAUDE.md`, `README.md`, `docs/`) e il flusso di controllo di quel
repository (`.github/`). Al loro posto ci sono questo README e il flusso di rilascio.

## Le release

A ogni tag il flusso [`Rilascio`](.github/workflows/rilascio.yml), con i log pubblici, crea una release
**immutabile** con:

- `timbro-<commit>-sorgenti.tar.gz`: i sorgenti di quella versione;
- per ogni file: marche temporali **qualificate eIDAS** di ACCV e di Sectigo (`.accv.tsr`, `.sectigo-q.tsr`) e
  la prova **OpenTimestamps** sulla blockchain Bitcoin (`.ots`);
- un'**attestazione di provenienza** di GitHub (Sigstore): «questo file l'ha prodotto questo flusso, da questo
  commit»;
- se l'app è cambiata, le **impronte dell'app** (`timbro-<commit>-impronte-app.txt`). L'app la costruisce il
  flusso stesso da questi sorgenti e la carica su Google Play, che la firma con la propria chiave (Play App
  Signing). Il bundle non si pubblica, perché contiene gli indirizzi del server: ne restano le impronte, che
  coincidono con il codice dell'app installata.

### Verificare una release

```sh
sha256sum -c SHA256SUMS
openssl ts -verify -data timbro-<commit>-sorgenti.tar.gz \
    -in timbro-<commit>-sorgenti.tar.gz.accv.tsr -CAfile timbro/stamping/roots/accv.pem
ots verify timbro-<commit>-sorgenti.tar.gz.ots          # prima "ots upgrade" se è ancora in attesa
gh attestation verify timbro-<commit>-sorgenti.tar.gz --repo <questo repository>
```

### Verificare l'app installata su un telefono

1. Ogni timbratura contiene l'attestazione hardware del telefono, che riporta il pacchetto
   `it.zawardo.timbro`, la versione e il certificato con cui Google Play ha firmato l'app.
2. Dal telefono: `adb shell pm path it.zawardo.timbro`, poi `adb pull` del `base.apk`, `unzip`, e
   `sha256sum classes*.dex`.
3. Le impronte devono coincidere con quelle in `base/dex/` del file `impronte-app.txt` della release di quella
   versione: lo stesso codice costruito qui dai sorgenti pubblici.

## La versione del server

Ogni timbratura registra la versione del server che l'ha ricevuta. Nel pacchetto di prova `record.json` la indica
con il collegamento alla sua release, e `verifica.py` controlla che lo script del pacchetto sia identico a quello
pubblicato qui per quella versione.

## Struttura

- `timbro/`: server (FastAPI, Python), con `verifica_pacchetto.py`, lo script di verifica dei pacchetti di prova;
- `android/`: app Android (Kotlin, Jetpack Compose);
- `tests/`: test del server;
- `strumenti/`: caricamento su Google Play usato dal flusso di rilascio.

## Licenza

Il codice è **di sola consultazione** (vedi [`LICENSE`](LICENSE)): si può leggere, studiare, compilare per
verificare che faccia quanto dichiarato, e `verifica.py` si può usare per verificare i pacchetti di prova. Non si
può riutilizzare, neanche in parte, in altri progetti. I file di terzi (caratteri, certificati pubblici, dati di
prova di altri progetti) restano sotto le loro licenze.
