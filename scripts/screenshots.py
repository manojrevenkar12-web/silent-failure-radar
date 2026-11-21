"""Screenshots of the built dashboard (desktop and phone), served from web/dist with the reports."""

from __future__ import annotations

import http.server
import shutil
import socketserver
import threading
from functools import partial
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "web" / "dist"
OUT = ROOT / "docs" / "screenshots"


def main() -> None:
    (DIST / "reports").mkdir(parents=True, exist_ok=True)
    for name in ("dev.json", "heldout.json"):
        if (ROOT / "reports" / name).exists():
            shutil.copyfile(ROOT / "reports" / name, DIST / "reports" / name)
    handler = partial(http.server.SimpleHTTPRequestHandler, directory=str(DIST))
    with socketserver.TCPServer(("127.0.0.1", 0), handler) as httpd:
        port = httpd.server_address[1]
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        OUT.mkdir(parents=True, exist_ok=True)
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=shutil.which("google-chrome") or None)
            for name, size in (("dashboard", {"width": 1280, "height": 900}), ("dashboard-phone", {"width": 390, "height": 844})):
                page = browser.new_page(viewport=size, device_scale_factor=2)
                which = "heldout" if (ROOT / "reports" / "heldout.json").exists() else "dev"
                page.goto(f"http://127.0.0.1:{port}/index.html?set={which}")
                page.wait_for_selector("figure[aria-label]")
                page.wait_for_timeout(1800)
                page.screenshot(path=str(OUT / f"{name}.png"), full_page=True)
            browser.close()
        httpd.shutdown()


if __name__ == "__main__":
    main()
