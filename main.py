"""Avvio del backend di Timbro (sul server: python main.py, nel container fastapi-timbro).

Un solo processo: niente --workers né --reload. uvicorn gestisce SIGTERM: chiude le richieste in corso (fino a
15 s), poi il lifespan ferma il worker (fino a 5 s) e libera il database. Infine si esce subito, senza aspettare
le chiamate di rete ancora in corso nei thread del worker (TSA, OTS: si ripetono al prossimo avvio), così si resta
nei 30 s concessi da Docker prima del SIGKILL.
"""

import logging
import os

import uvicorn

from timbro.config import get_settings
from timbro.log_setup import setup_logging


def main() -> None:
    settings = get_settings()
    setup_logging(settings.log_dir)

    from timbro.app import create_app

    uvicorn.run(
        create_app(settings),
        host=settings.web_host,
        port=settings.web_port,
        log_config=None,  # i log li configura setup_logging (file a rotazione, segreti oscurati)
        access_log=False,  # le richieste le registra già nginx
        server_header=False,
        timeout_graceful_shutdown=15,
    )
    logging.shutdown()
    os._exit(0)


if __name__ == "__main__":
    main()
