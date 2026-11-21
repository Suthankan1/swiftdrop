"""Headless browser acceptance used only with the explicitly enabled live probe."""
import csv
from datetime import datetime
import io
import json
import os
from pathlib import Path
import re
import uuid
from urllib.parse import urlsplit

from playwright.sync_api import Error as PlaywrightError, expect, sync_playwright


class MerchantBrowserProbe:
    def __init__(self, parcel, output):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.parcel = parcel
        self.results = []
        self.runtime = sync_playwright().start()
        options = {"headless": True}
        if os.environ.get("SWIFTDROP_BROWSER_EXECUTABLE"):
            options["executable_path"] = os.environ["SWIFTDROP_BROWSER_EXECUTABLE"]
        self.browser = self.runtime.chromium.launch(**options)
        self.context = self.browser.new_context(accept_downloads=True, viewport={"width": 1440, "height": 1000}, timezone_id="Asia/Colombo")
        self.page = self.context.new_page()
        self.authoritative_updated_at = None
        self.page.on("response", self.capture_parcel_response)
        self.page.set_default_timeout(45000)
        self.page.set_default_navigation_timeout(180000)

    def capture_parcel_response(self, response):
        path = urlsplit(response.url).path
        if response.request.method != "GET" or path not in ("/api/v1/parcels", "/api/v1/parcels/" + self.parcel["parcelId"]) or response.status != 200:
            return
        try:
            data = response.json()
            rows = data.get("items", [data])
            for row in rows:
                if row.get("parcelId") == self.parcel["parcelId"] and row.get("updatedAt"):
                    self.authoritative_updated_at = row["updatedAt"]
        except (ValueError, TypeError, PlaywrightError):
            pass

    def login(self, username, password):
        self.page.goto("http://localhost:5173/merchant/orders")
        self.page.get_by_role("button", name="Sign in", exact=True).click()
        self.page.locator("#username").fill(username)
        self.page.locator("#password").fill(password)
        self.page.locator("#kc-login").click(no_wait_after=True)
        self.page.wait_for_url("http://localhost:5173/**", timeout=180000)
        expect(self.page.get_by_role("navigation", name="Merchant space sections")).to_be_visible(timeout=180000)
        self.page.goto("http://localhost:5173/merchant/orders/" + self.parcel["parcelId"])
        self.page.get_by_role("button", name="Inspect public tracking checkpoints").click()
        expect(self.page.get_by_role("dialog")).to_be_visible()
        expect(self.page.get_by_label("Live updates active")).to_be_visible(timeout=60000)
        self.record("real browser PKCE login, direct merchant URL and live tracking subscription")
        self.page.screenshot(path=str(self.output / "tracking-before-transition.png"), full_page=True)

    def record(self, label, **details):
        self.results.append({"step": label, "status": "Passed", **details})
        (self.output / "browser-results.json").write_text(json.dumps(self.results, indent=2), encoding="utf-8")
        print("BROWSER PASS: " + label, flush=True)

    def delivered(self):
        dialog = self.page.get_by_role("dialog")
        expect(dialog.locator(".tracking-status-banner").get_by_text("Delivered", exact=True)).to_be_visible(timeout=60000)
        expect(dialog.get_by_role("button", name=re.compile("Download PDF receipt"))).to_be_enabled()
        self.page.screenshot(path=str(self.output / "tracking-live-delivered.png"), full_page=True)
        self.record("actual courier DELIVERED transition rendered in open Merchant tracking UI")
        with self.page.expect_download() as pending:
            dialog.get_by_role("button", name=re.compile("Download PDF receipt")).click()
        download = pending.value
        receipt = self.output / "browser-downloaded-receipt.pdf"
        download.save_as(receipt)
        from pypdf import PdfReader
        text = "\n".join(page.extract_text() for page in PdfReader(receipt).pages)
        assert self.parcel["trackingNumber"] in text
        assert f'{self.parcel["amountMinorUnits"] / 100:.2f}' in text.replace(",", "")
        self.record("completed browser PDF download and parsed reference/fee")
        self.page.get_by_role("button", name="Close tracking details modal").click()

    def finish(self, zone):
        page = self.page
        routes = {"overview": "/merchant", "orders": "/merchant/orders", "new-order": "/merchant/orders/new", "credentials": "/merchant/credentials", "webhooks": "/merchant/webhooks"}
        for width in (360, 390, 768, 1024, 1440):
            page.set_viewport_size({"width": width, "height": 1000})
            for name, route in routes.items():
                page.goto("http://localhost:5173" + route)
                expect(page.get_by_role("navigation", name="Merchant space sections")).to_be_visible()
                page.wait_for_function("!document.querySelector('[aria-busy=\"true\"]')", timeout=60000)
                if name == "overview":
                    expect(page.get_by_role("heading", name="Synthetic Merchant Acceptance")).to_be_visible()
                elif name == "orders":
                    expect(page.get_by_role("table", name="Merchant orders list")).to_be_visible()
                elif name == "new-order":
                    expect(page.get_by_label("Pickup zone", exact=True)).to_be_enabled()
                elif name == "credentials":
                    expect(page.get_by_role("button", name=re.compile("Generate API Key"))).to_be_enabled()
                elif name == "webhooks":
                    expect(page.locator(".webhook-card").first).to_be_visible()
                dimensions = page.evaluate("""() => ({viewport: innerWidth, document: document.documentElement.scrollWidth,
                    links: [...document.querySelectorAll('.merchant-sub-nav a')].map(a => ({label: a.textContent.trim(), left: a.getBoundingClientRect().left, right: a.getBoundingClientRect().right}))})""")
                page.screenshot(path=str(self.output / f"{name}-{width}.png"), full_page=True)
                assert dimensions["document"] <= width + 1, f"{name} document overflow at {width}: {dimensions['document']}"
                assert all(link["left"] >= -1 and link["right"] <= width + 1 for link in dimensions["links"]), f"Navigation clipped at {width}"
                self.record(f"{name} responsive screenshot and reachable navigation at {width}px", **dimensions)
        page.set_viewport_size({"width": 1440, "height": 1000})
        page.goto("http://localhost:5173/merchant/orders")
        expect(page.get_by_role("table", name="Merchant orders list")).to_be_visible()
        search = page.get_by_role("searchbox", name="Search orders by tracking number or parcel ID")
        search.fill(self.parcel["trackingNumber"])
        expect(page.locator(".merchant-order-row")).to_have_count(1)
        page.get_by_role("radio", name="Delivered", exact=True).click()
        expect(page.locator(".merchant-order-row")).to_have_count(1)
        page.get_by_role("button", name="Clear search query").click()
        page.get_by_role("radio", name="All Orders", exact=True).click()
        self.record("real dashboard combined search/filter and clear controls")
        with page.expect_download() as pending:
            page.get_by_role("button", name=re.compile("Export Orders to CSV")).click()
        exported = self.output / "browser-exported-orders.csv"
        pending.value.save_as(exported)
        rows = list(csv.DictReader(io.StringIO(exported.read_text(encoding="utf-8-sig"))))
        delivered = next(row for row in rows if row["Tracking Number"] == self.parcel["trackingNumber"])
        assert delivered["Status"] == "DELIVERED"
        assert delivered["Last Update (ISO UTC)"] != delivered["Created At (ISO UTC)"]
        assert self.authoritative_updated_at
        assert datetime.fromisoformat(delivered["Last Update (ISO UTC)"]) == datetime.fromisoformat(self.authoritative_updated_at)
        assert delivered["Fee"] == f'{self.parcel["amountMinorUnits"] / 100:.2f}'
        assert delivered["Currency"] == "LKR"
        self.record("completed browser CSV export with delivered status, currency and authoritative update", rowCount=len(rows))
        page.get_by_role("button", name=re.compile("Bulk CSV Upload")).click()
        dialog = page.get_by_role("dialog")
        expect(dialog).to_be_visible()
        page.keyboard.press("Tab")
        assert dialog.evaluate("el => el.contains(document.activeElement)")
        with page.expect_download() as pending:
            dialog.get_by_role("button", name=re.compile("Download Sample Template")).click()
        sample = self.output / "browser-sample-template.csv"
        pending.value.save_as(sample)
        sample_rows = list(csv.DictReader(io.StringIO(sample.read_text(encoding="utf-8-sig"))))
        assert len(sample_rows) == 2 and "merchantReference" in sample_rows[0]
        dialog.locator('input[type="file"]').set_input_files({"name": "invalid.csv", "mimeType": "text/csv", "buffer": b"invalid,headers\n1,2\n"})
        expect(dialog.get_by_role("button", name=re.compile("Import .* orders"))).to_be_disabled()
        dialog.get_by_role("button", name="Change File").click()
        sample_row = sample_rows[0]
        sample_row.update(pickupZone=zone, dropoffZone=zone, pickupAddress="Synthetic browser pickup", dropoffAddress="Synthetic browser destination", recipientName="Synthetic Browser Recipient", recipientPhone="+94770000000", merchantReference="MQBROWSER-" + uuid.uuid4().hex)
        buffer = io.StringIO(newline="")
        writer = csv.DictWriter(buffer, fieldnames=list(sample_row))
        writer.writeheader()
        writer.writerow(sample_row)
        dialog.locator('input[type="file"]').set_input_files({"name": "synthetic-order.csv", "mimeType": "text/csv", "buffer": buffer.getvalue().encode()})
        expect(dialog.get_by_role("button", name="Import 1 orders")).to_be_enabled()
        for attempt in range(3):
            dialog.get_by_role("button", name="Import 1 orders").click()
            page.wait_for_function("!document.querySelector('[role=\"dialog\"] button[aria-busy=\"true\"]')", timeout=60000)
            if dialog.get_by_text(re.compile("1.*booked", re.I)).first.is_visible():
                break
            if attempt < 2:
                self.record("bounded CSV import retry with retained row idempotency", attempt=attempt + 1)
        expect(dialog.get_by_text(re.compile("1.*booked", re.I)).first).to_be_visible(timeout=60000)
        page.screenshot(path=str(self.output / "bulk-import-success.png"), full_page=True)
        self.record("browser template download, invalid-file prevention and real CSV import")
        page.keyboard.press("Escape")
        expect(dialog).not_to_be_visible()
        self.record("bulk dialog keyboard focus, Escape and closure")
        page.goto("http://localhost:5173/merchant/orders/new")
        expect(page.get_by_label("Pickup zone", exact=True)).to_be_enabled()
        page.get_by_label("Pickup zone", exact=True).select_option(zone)
        page.get_by_label("Drop-off zone", exact=True).select_option(zone)
        for label, value in {
            "Pickup address": "Synthetic quote pickup", "Drop-off address": "Synthetic quote destination",
            "Recipient name": "Synthetic Recipient", "Recipient phone": "+94770000000",
            "Your order reference": "MQQUOTE-" + uuid.uuid4().hex,
        }.items():
            page.get_by_label(label, exact=True).fill(value)
        booking_requests = []
        page.on("request", lambda request: booking_requests.append(True) if request.method == "POST" and re.search(r"/api/v1/(merchant/)?parcels$", request.url) else None)
        page.get_by_role("button", name="Review price", exact=True).click()
        expect(page.get_by_role("heading", name="Review your quote")).to_be_visible()
        expect(page.get_by_role("button", name="Confirm and book order")).to_be_enabled()
        assert not booking_requests, "Price review unexpectedly created an order"
        page.screenshot(path=str(self.output / "quote-confirmation.png"), full_page=True)
        page.get_by_role("button", name="Cancel quote and edit").click()
        expect(page.get_by_label("Recipient name", exact=True)).to_have_value("Synthetic Recipient")
        assert not booking_requests, "Cancelling the quote created an order"
        self.record("real quote review, explicit confirmation control and cancellation preserving input without booking")
        page.goto("http://localhost:5173/merchant/credentials")
        generate = page.get_by_role("button", name=re.compile("Generate API Key"))
        generate.click()
        dialog = page.get_by_role("dialog")
        expect(dialog).to_be_visible()
        page.keyboard.press("Tab")
        assert dialog.evaluate("el => el.contains(document.activeElement)")
        page.keyboard.press("Escape")
        expect(dialog).not_to_be_visible()
        expect(generate).to_be_focused()
        self.record("API-key dialog keyboard focus, Escape and focus restoration")
        page.goto("http://localhost:5173/merchant/webhooks")
        page.get_by_role("button", name="Delivery Logs", exact=True).first.click()
        dialog = page.get_by_role("dialog")
        expect(dialog.get_by_role("heading", name="Webhook Delivery Logs")).to_be_visible()
        expect(dialog.get_by_role("table")).to_be_visible(timeout=60000)
        page.screenshot(path=str(self.output / "real-webhook-delivery-logs.png"), full_page=True)
        page.keyboard.press("Escape")
        expect(dialog).not_to_be_visible()
        self.record("real webhook delivery logs and keyboard closure")
        page.set_viewport_size({"width": 390, "height": 1000})
        page.get_by_role("button", name="Open Help Assistant", exact=True).click()
        assistant = page.get_by_role("dialog", name="SwiftDrop Help Assistant")
        expect(assistant).to_be_visible()
        page.screenshot(path=str(self.output / "help-assistant-390.png"), full_page=True)
        assistant.get_by_role("button", name="Dismiss Help Assistant").click()
        expect(assistant).not_to_be_visible()
        self.record("mobile Help Assistant opening and dismissal")

    def diagnostics(self):
        if self.page.url.startswith("http://localhost:5173/"):
            self.page.screenshot(path=str(self.output / "browser-failure.png"), full_page=True)
            (self.output / "browser-failure.txt").write_text(self.page.locator("body").inner_text(), encoding="utf-8")

    def close(self):
        self.context.close()
        self.browser.close()
        self.runtime.stop()
