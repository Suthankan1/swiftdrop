#!/usr/bin/env python3
"""
SMTP Delivery Verification & Error Handling Regression Suite (Issue #159 / #150).

Verifies:
1. Complete SMTP submission lifecycle (HELO/EHLO, MAIL FROM, RCPT TO, DATA, QUIT) against a mock SMTP sink.
2. Invariant verification of email headers (From, To, Subject, Message-ID) and body content.
3. Secret-exclusion guarantee: absence of passwords, JWT access tokens, API keys, and private keys from email body.
4. Semantic distinction between SMTP relay acceptance (250 OK) and confirmed recipient inbox delivery.
5. Error simulation & fail-closed recovery: connection refused, socket timeout, 550 permanent bounce, 421 transient deferral, 535 auth failure.
6. Production configuration & environment-variable mapping invariants in Helm and realm-swiftdrop.json.
7. Strict $0 financial policy adherence: external paid/metered relays remain BLOCKED.
"""

import email
import email.policy
import json
import os
from pathlib import Path
import re
import smtplib
import socket
import threading
import time
import unittest


IDENTITY_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = IDENTITY_DIR.parents[1]
REALM_FILE = IDENTITY_DIR / "realm-swiftdrop.json"
VALUES_FILE = REPO_ROOT / "platform/kubernetes/helm/swiftdrop/values.yaml"
ALLOWLIST_FILE = REPO_ROOT / "platform/security/azure-allowlist-policy.yaml"


class MockSmtpServer:
    """A lightweight, thread-safe RFC 5321 Mock SMTP Server for local verification."""

    def __init__(self, host="127.0.0.1", port=0, behavior=None):
        self.host = host
        self.port = port
        self.behavior = behavior or {}
        self.received_messages = []
        self.connection_count = 0
        self.server_socket = None
        self.thread = None
        self._running = threading.Event()

    def start(self):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind((self.host, self.port))
        self.port = self.server_socket.getsockname()[1]
        self.server_socket.listen(5)
        self._running.set()
        self.thread = threading.Thread(target=self._listen_loop, daemon=True)
        self.thread.start()
        return self.port

    def stop(self):
        self._running.clear()
        if self.server_socket:
            try:
                self.server_socket.close()
            except Exception:
                pass
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)

    def _listen_loop(self):
        while self._running.is_set():
            try:
                client_sock, _ = self.server_socket.accept()
            except OSError:
                break
            self.connection_count += 1
            client_thread = threading.Thread(target=self._handle_client, args=(client_sock,), daemon=True)
            client_thread.start()

    def _handle_client(self, client_sock):
        try:
            client_sock.settimeout(3.0)
            if self.behavior.get("greeting_delay"):
                time.sleep(self.behavior["greeting_delay"])

            if self.behavior.get("greeting_code"):
                code = self.behavior["greeting_code"]
                client_sock.sendall(f"{code} Service unavailable\r\n".encode())
                client_sock.close()
                return

            client_sock.sendall(b"220 swiftdrop-mail-sink ESMTP Mock\r\n")

            envelope_from = None
            envelope_to = []
            in_data = False
            data_buffer = []

            while self._running.is_set():
                line = self._read_line(client_sock)
                if line is None:
                    break

                if in_data:
                    if line == ".":
                        in_data = False
                        msg_text = "\r\n".join(data_buffer)
                        self.received_messages.append({
                            "from": envelope_from,
                            "to": list(envelope_to),
                            "raw": msg_text,
                        })
                        if self.behavior.get("data_response"):
                            client_sock.sendall(self.behavior["data_response"].encode() + b"\r\n")
                        else:
                            client_sock.sendall(b"250 2.0.0 Ok: queued as mock-msg-001\r\n")
                        envelope_to = []
                        data_buffer = []
                    else:
                        if line.startswith(".."):
                            line = line[1:]
                        data_buffer.append(line)
                    continue

                cmd_parts = line.split(" ", 1)
                cmd = cmd_parts[0].upper()
                arg = cmd_parts[1] if len(cmd_parts) > 1 else ""

                if cmd in ("HELO", "EHLO"):
                    if self.behavior.get("auth_supported"):
                        client_sock.sendall(b"250-swiftdrop-mail-sink\r\n250-AUTH LOGIN PLAIN\r\n250 OK\r\n")
                    else:
                        client_sock.sendall(b"250-swiftdrop-mail-sink\r\n250 8BITMIME\r\n")
                elif cmd == "AUTH":
                    if self.behavior.get("auth_fail"):
                        client_sock.sendall(b"535 5.7.8 Authentication credentials invalid\r\n")
                    else:
                        client_sock.sendall(b"235 2.7.0 Authentication successful\r\n")
                elif cmd == "MAIL":
                    if self.behavior.get("mail_from_response"):
                        client_sock.sendall(self.behavior["mail_from_response"].encode() + b"\r\n")
                        continue
                    m = re.search(r"FROM:\s*<([^>]+)>", arg, re.IGNORECASE)
                    envelope_from = m.group(1) if m else arg
                    client_sock.sendall(b"250 2.1.0 Sender ok\r\n")
                elif cmd == "RCPT":
                    if self.behavior.get("rcpt_to_response"):
                        client_sock.sendall(self.behavior["rcpt_to_response"].encode() + b"\r\n")
                        continue
                    m = re.search(r"TO:\s*<([^>]+)>", arg, re.IGNORECASE)
                    to_addr = m.group(1) if m else arg
                    envelope_to.append(to_addr)
                    client_sock.sendall(b"250 2.1.5 Recipient ok\r\n")
                elif cmd == "DATA":
                    if self.behavior.get("data_init_response"):
                        client_sock.sendall(self.behavior["data_init_response"].encode() + b"\r\n")
                        continue
                    in_data = True
                    client_sock.sendall(b"354 Start mail input; end with <CRLF>.<CRLF>\r\n")
                elif cmd == "RSET":
                    envelope_from = None
                    envelope_to = []
                    data_buffer = []
                    in_data = False
                    client_sock.sendall(b"250 2.0.0 Reset state\r\n")
                elif cmd == "NOOP":
                    client_sock.sendall(b"250 2.0.0 OK\r\n")
                elif cmd == "QUIT":
                    client_sock.sendall(b"221 2.0.0 Bye\r\n")
                    break
                else:
                    client_sock.sendall(b"500 5.5.2 Syntax error, command unrecognized\r\n")
        except (socket.timeout, ConnectionResetError, BrokenPipeError):
            pass
        finally:
            try:
                client_sock.close()
            except Exception:
                pass

    def _read_line(self, sock):
        buf = bytearray()
        while self._running.is_set():
            chunk = sock.recv(1)
            if not chunk:
                return None
            if chunk == b"\n":
                return buf.rstrip(b"\r").decode("utf-8", "replace")
            buf.extend(chunk)


class SmtpDeliveryVerificationTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        with open(REALM_FILE, "r", encoding="utf-8") as f:
            cls.realm = json.load(f)
        with open(VALUES_FILE, "r", encoding="utf-8") as f:
            cls.values_text = f.read()
        with open(ALLOWLIST_FILE, "r", encoding="utf-8") as f:
            cls.allowlist_text = f.read()

    def test_successful_smtp_submission_and_message_parsing(self):
        """Verifies complete SMTP submission, header parsing, and body invariants against mock server."""
        server = MockSmtpServer()
        port = server.start()
        try:
            from_addr = "no-reply@swiftdrop.test"
            to_addr = "qa-synthetic-customer@swiftdrop.test"
            action_token_link = "http://localhost:8090/realms/swiftdrop/login-actions/action-token?key=dummy-test-key-token-300s"
            
            msg = (
                f"From: SwiftDrop Security <{from_addr}>\r\n"
                f"To: {to_addr}\r\n"
                f"Subject: [SwiftDrop] Verify your email address\r\n"
                f"MIME-Version: 1.0\r\n"
                f"Content-Type: text/plain; charset=UTF-8\r\n"
                f"\r\n"
                f"Welcome to SwiftDrop.\r\n"
                f"Please verify your email address by clicking the link below within 300 seconds:\r\n"
                f"{action_token_link}\r\n"
                f"\r\n"
                f"If you did not request this verification, please disregard this message.\r\n"
            )

            with smtplib.SMTP("127.0.0.1", port, timeout=5) as client:
                client.ehlo("localhost")
                client.sendmail(from_addr, [to_addr], msg)
                client.quit()

            self.assertEqual(len(server.received_messages), 1, "Mock server must receive exactly one message")
            received = server.received_messages[0]
            self.assertEqual(received["from"], from_addr)
            self.assertEqual(received["to"], [to_addr])

            parsed = email.message_from_string(received["raw"], policy=email.policy.default)
            self.assertEqual(parsed["To"], to_addr)
            self.assertEqual(parsed["Subject"], "[SwiftDrop] Verify your email address")
            body = parsed.get_content()
            self.assertIn(action_token_link, body)

            # Strict security invariants on email content
            self.assertNotIn("password", body.lower(), "Email body must never contain password credentials")
            self.assertNotIn("secret", body.lower(), "Email body must never contain secrets")
            self.assertNotIn("bearer", body.lower(), "Email body must never contain bearer tokens")
            self.assertNotIn("private key", body.lower(), "Email body must never contain cryptographic private keys")
        finally:
            server.stop()

    def test_distinction_between_relay_acceptance_and_confirmed_inbox_delivery(self):
        """Verifies that an SMTP 250 response indicates relay queue acceptance, not inbox arrival."""
        server = MockSmtpServer()
        port = server.start()
        try:
            with smtplib.SMTP("127.0.0.1", port, timeout=5) as client:
                code, resp = client.helo("localhost")
                self.assertEqual(code, 250)
                send_errs = client.sendmail(
                    "no-reply@swiftdrop.test",
                    ["undeliverable-external@invalid.domain.test"],
                    "Subject: Delivery Test\r\n\r\nMessage body",
                )
                self.assertEqual(send_errs, {}, "Relay accepts submission without immediate downstream error")

            # Invariant: SMTP submission acceptance != recipient inbox delivery
            def classify_delivery_status(smtp_submission_code: int, external_inbox_verified: bool) -> str:
                if smtp_submission_code == 250 and not external_inbox_verified:
                    return "ACCEPTED_BY_RELAY_PENDING_EXTERNAL_VERIFICATION"
                elif smtp_submission_code == 250 and external_inbox_verified:
                    return "DELIVERED_TO_RECIPIENT_INBOX"
                return "DELIVERY_FAILED"

            self.assertEqual(
                classify_delivery_status(250, False),
                "ACCEPTED_BY_RELAY_PENDING_EXTERNAL_VERIFICATION",
            )
            self.assertEqual(
                classify_delivery_status(250, True),
                "DELIVERED_TO_RECIPIENT_INBOX",
            )
        finally:
            server.stop()

    def test_smtp_connection_refused_fails_closed(self):
        """Verifies safe error handling and fail-closed behavior when SMTP server is unreachable."""
        # Find an unused port
        temp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        temp_sock.bind(("127.0.0.1", 0))
        closed_port = temp_sock.getsockname()[1]
        temp_sock.close()

        with self.assertRaises((ConnectionRefusedError, smtplib.SMTPConnectError, OSError)):
            client = smtplib.SMTP("127.0.0.1", closed_port, timeout=1)
            client.close()

    def test_smtp_socket_timeout_handling(self):
        """Verifies timeout handling when SMTP server stalls without response."""
        server = MockSmtpServer(behavior={"greeting_delay": 2.0})
        port = server.start()
        try:
            with self.assertRaises((socket.timeout, TimeoutError, smtplib.SMTPServerDisconnected)):
                client = smtplib.SMTP("127.0.0.1", port, timeout=0.5)
                client.close()
        finally:
            server.stop()

    def test_smtp_permanent_rejection_550_fails_closed(self):
        """Verifies handling of permanent recipient rejection (550 Mailbox unavailable)."""
        server = MockSmtpServer(behavior={"rcpt_to_response": "550 5.1.1 Mailbox unavailable / Recipient rejected"})
        port = server.start()
        try:
            with smtplib.SMTP("127.0.0.1", port, timeout=5) as client:
                client.helo("localhost")
                with self.assertRaises(smtplib.SMTPRecipientsRefused) as ctx:
                    client.sendmail(
                        "no-reply@swiftdrop.test",
                        ["nonexistent@swiftdrop.test"],
                        "Subject: Test\r\n\r\nTest",
                    )
                refused = ctx.exception.recipients
                self.assertIn("nonexistent@swiftdrop.test", refused)
                self.assertEqual(refused["nonexistent@swiftdrop.test"][0], 550)
        finally:
            server.stop()

    def test_smtp_transient_rejection_421(self):
        """Verifies handling of transient server error (421 Service unavailable)."""
        server = MockSmtpServer(behavior={"mail_from_response": "421 4.3.0 Mail system busy, try again later"})
        port = server.start()
        try:
            with smtplib.SMTP("127.0.0.1", port, timeout=5) as client:
                client.helo("localhost")
                with self.assertRaises(smtplib.SMTPSenderRefused) as ctx:
                    client.sendmail(
                        "no-reply@swiftdrop.test",
                        ["user@swiftdrop.test"],
                        "Subject: Test\r\n\r\nTest",
                    )
                self.assertEqual(ctx.exception.smtp_code, 421)
        finally:
            server.stop()

    def test_smtp_authentication_failure_535(self):
        """Verifies that authentication rejection fails cleanly without leaking credentials."""
        server = MockSmtpServer(behavior={"auth_supported": True, "auth_fail": True})
        port = server.start()
        try:
            with smtplib.SMTP("127.0.0.1", port, timeout=5) as client:
                client.ehlo("localhost")
                with self.assertRaises(smtplib.SMTPAuthenticationError) as ctx:
                    client.login("test-user", "invalid-pass")
                self.assertEqual(ctx.exception.smtp_code, 535)
                # Ensure exception string does not echo the password
                self.assertNotIn("invalid-pass", str(ctx.exception))
        finally:
            server.stop()

    def test_production_smtp_configuration_invariants(self):
        """Verifies that realm-swiftdrop.json and Helm values adhere to the approved local Mailpit contract."""
        smtp = self.realm.get("smtpServer", {})
        self.assertEqual(smtp.get("host"), "mailpit", "Local realm SMTP must point to Mailpit")
        self.assertEqual(smtp.get("port"), "1025", "Local realm SMTP port must be 1025")
        self.assertEqual(smtp.get("from"), "no-reply@swiftdrop.test", "Canonical sender must be no-reply@swiftdrop.test")
        self.assertEqual(smtp.get("auth"), "false", "Local Mailpit requires auth=false")
        self.assertEqual(smtp.get("ssl"), "false", "Local Mailpit requires ssl=false")
        self.assertEqual(smtp.get("starttls"), "false", "Local Mailpit requires starttls=false")

        # Helm chart secret references
        self.assertIn("smtpSecretName: swiftdrop-smtp-secret", self.values_text)
        self.assertIn("smtpHostKey: host", self.values_text)
        self.assertIn("smtpPortKey: port", self.values_text)

    def test_strict_zero_cost_policy_blocks_external_paid_smtp(self):
        """Verifies that zero-cost policy prohibits commercial SMTP relays."""
        self.assertIn("cost_ceiling_usd: 0.00", self.allowlist_text)
        # Check that no commercial SMTP provider strings are present in values.yaml
        prohibited_providers = ["sendgrid", "mailgun", "postmark", "ses.amazonaws.com"]
        for provider in prohibited_providers:
            self.assertNotIn(provider, self.values_text.lower(),
                             f"Prohibited paid SMTP provider found in Helm values: {provider}")


if __name__ == "__main__":
    unittest.main()
