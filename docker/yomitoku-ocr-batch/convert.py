#!/usr/bin/env python3
"""Convert all PDF files in DATA_DIR to Markdown using YomiToku OCR via REST API."""
import os
import sys
import logging
import requests
import fitz
from datetime import datetime, timezone, timedelta
from pathlib import Path

# ISO 8601 + JST (+09:00) logging
_JST = timezone(timedelta(hours=9))

class _JSTFormatter(logging.Formatter):
    def formatTime(self, record, datefmt=None):
        return datetime.fromtimestamp(record.created, _JST).isoformat(timespec='milliseconds')

_handler = logging.StreamHandler()
_handler.setFormatter(_JSTFormatter('%(asctime)s - %(levelname)s - %(message)s'))
logging.root.handlers = [_handler]
logging.root.setLevel(logging.INFO)
logger = logging.getLogger(__name__)

DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
OCR_SERVER = os.environ.get("OCR_SERVER", "http://192.168.5.17:8013")
OCR_TIMEOUT = 600  # 10 minutes per page


def convert_pdf(pdf_path: Path) -> None:
    md_path = pdf_path.with_suffix(".md")
    logger.info(f"Converting {pdf_path.name} ...")

    try:
        # Get page count
        doc = fitz.open(str(pdf_path))
        page_count = len(doc)
        doc.close()

        parts = []
        for page_num in range(page_count):
            logger.info(f"  page {page_num + 1}/{page_count}")

            # Retry with exponential backoff (max 5 attempts)
            for attempt in range(5):
                try:
                    with open(pdf_path, "rb") as f:
                        files = {"file": f}
                        data = {"page": str(page_num)}
                        response = requests.post(
                            f"{OCR_SERVER}/ocr/markdown",
                            files=files,
                            data=data,
                            timeout=OCR_TIMEOUT
                        )
                    response.raise_for_status()
                    result = response.json()
                    markdown = result.get("markdown", "")
                    if markdown:
                        parts.append(markdown)
                    break  # Success, exit retry loop
                except requests.RequestException as e:
                    if attempt < 4:
                        wait_time = 2 ** attempt  # 1, 2, 4, 8 seconds
                        logger.warning(f"    Retry {attempt + 1}/5 after {wait_time}s: {e}")
                        import time
                        time.sleep(wait_time)
                    else:
                        logger.error(f"    Failed after 5 attempts: {e}")
                        raise

        full_markdown = "\n\n".join(parts)
        md_path.write_text(full_markdown, encoding="utf-8")
        logger.info(f"  -> {md_path.name}")
    except requests.RequestException as e:
        logger.error(f"API error: {e}")
        raise


if __name__ == "__main__":
    pdfs = sorted(DATA_DIR.glob("*.pdf")) + sorted(DATA_DIR.glob("*.PDF"))
    if not pdfs:
        logger.info(f"No PDF files found in {DATA_DIR}")
        sys.exit(0)

    logger.info(f"Found {len(pdfs)} PDF(s). Connecting to {OCR_SERVER} ...")

    # Health check
    try:
        resp = requests.get(f"{OCR_SERVER}/", timeout=10)
        resp.raise_for_status()
        logger.info(f"OCR server ready: {resp.json()}")
    except Exception as e:
        logger.error(f"OCR server unreachable: {e}")
        sys.exit(1)

    errors = []
    for pdf in pdfs:
        try:
            convert_pdf(pdf)
        except Exception as e:
            logger.error(f"{pdf.name}: {e}")
            errors.append(pdf.name)

    if errors:
        logger.error(f"Failed: {errors}")
        sys.exit(1)

    logger.info(f"Done. Converted {len(pdfs)} PDF(s).")
