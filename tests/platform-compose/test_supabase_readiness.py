"""Readiness gates tested with synthetic inputs and mocked subprocesses only."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("supabase_readiness", ROOT / "platform/compose/supabase.py")
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


def values():
    result = {}
    for group in ("ACCOUNTS", "BUSINESS", "IDENTITY"):
        result[f"SUPABASE_{group}_HOST"] = f"{group.lower()}.example.invalid"
        result[f"SUPABASE_{group}_ADMIN_USERNAME"] = f"postgres.{group.lower()}ref"
    for service, (group, role, database) in helper.SERVICES.items():
        result[f"{service}_DB_URL"] = (
            f"jdbc:postgresql://{result[f'SUPABASE_{group}_HOST']}:5432/{database}?sslmode=require"
        )
        result[f"{service}_DB_USERNAME"] = f"{role}.{group.lower()}ref"
        result[f"{service}_DB_PASSWORD"] = "synthetic-password-for-test-only"
    return result


def ready(database, role):
    return dict(database=database, role=role, session_role=role, tls=True,
                safe_role=True, owner=True, connect=True, schema=True,
                foreign_connect=False, tables=True, sequences=True, history=True)


def runtime_response(_, host, database, username, password, sql, *args):
    if "FROM public.flyway_schema_history" in sql:
        migrations = ROOT / "services" / (database.removesuffix("_db") + "-service") / "src/main/resources/db/migration"
        return {"migrations": [
            {"version": path.name.split("__", 1)[0][1:], "script": path.name, "success": True}
            for path in migrations.glob("V*__*.sql")
        ]}
    return ready(database, username.split(".")[0])


def psql_result(data, tls=True):
    preamble = "SSL connection (protocol: TLSv1.3, cipher: test)\n" if tls else "No SSL connection\n"
    return subprocess.CompletedProcess([], 0, preamble + json.dumps(data) + "\n", "")


class EnvironmentTests(unittest.TestCase):
    def read(self, contents, mode=0o600):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text(contents)
            path.chmod(mode)
            return helper.read_env(path)

    def test_literal_password_characters_are_preserved_without_execution(self):
        data = self.read("TEST_SETTING='synthetic@#$(touch never)`false`$literal'\nRAW=synthetic@#value\n")
        self.assertEqual(data["TEST_SETTING"], "synthetic@#$(touch never)`false`$literal")
        self.assertEqual(data["RAW"], "synthetic@#value")

    def test_quoted_password_whitespace_is_preserved(self):
        self.assertEqual(self.read("TEST_SETTING=' leading and trailing '\n")["TEST_SETTING"], " leading and trailing ")

    def test_insecure_file_permissions_and_duplicate_names_are_rejected(self):
        with self.assertRaises(helper.CheckError):
            self.read("TEST_SETTING=synthetic\n", mode=0o644)
        with self.assertRaises(helper.CheckError):
            self.read("TEST_SETTING=one\nTEST_SETTING=two\n")


class TargetTests(unittest.TestCase):
    def test_correct_named_database_and_tls_are_required(self):
        data = values()
        url = data["ACCOUNTS_DB_URL"]
        bad = (
            url.replace("accounts_db", "postgres"),
            url.replace("sslmode=require", "sslmode=disable"),
            url.replace(":5432/", ":6543/"),
            url.replace("accounts.example.invalid", "business.example.invalid"),
            url.replace("//", "//plainuser@", 1),
            url + "&user=denied",
        )
        for invalid in bad:
            with self.subTest(url=invalid), self.assertRaises(helper.CheckError):
                helper.target(dict(data, ACCOUNTS_DB_URL=invalid), "ACCOUNTS")


class PostgresTests(unittest.TestCase):
    @patch.object(helper.shutil, "which", return_value="/synthetic/psql")
    def test_secret_in_environment_only_and_queries_read_only(self, _):
        data = {"tls": False}
        with patch.object(helper.subprocess, "run", return_value=psql_result(data)) as run:
            actual = helper.postgres({}, "example.invalid", "accounts_db", "accounts_app.ref", "synthetic@#", "SELECT 1;")
        args, kwargs = run.call_args
        self.assertNotIn("synthetic@#", " ".join(args[0]))
        self.assertEqual(kwargs["env"]["PGPASSWORD"], "synthetic@#")
        self.assertEqual(kwargs["env"]["PGSSLMODE"], "require")
        self.assertIn("default_transaction_read_only=on", kwargs["env"]["PGOPTIONS"])
        self.assertTrue(kwargs["capture_output"])
        self.assertTrue(actual["tls"])
        self.assertFalse(actual["upstream_tls"])

    @patch.object(helper.shutil, "which", return_value="/synthetic/psql")
    def test_no_client_tls_is_not_accepted_as_tls(self, _):
        result = psql_result({"tls": False}, tls=False)
        with patch.object(helper.subprocess, "run", return_value=result):
            actual = helper.postgres({}, "example.invalid", "accounts_db", "accounts_app.ref", "synthetic", "SELECT 1;")
        self.assertFalse(actual["tls"])

    @patch.object(helper.shutil, "which", return_value="/synthetic/psql")
    def test_subprocess_diagnostics_never_expose_password(self, _):
        result = subprocess.CompletedProcess([], 2, "", "password authentication failed synthetic-secret")
        with patch.object(helper.subprocess, "run", return_value=result):
            with self.assertRaises(helper.CheckError) as caught:
                helper.postgres({}, "example.invalid", "accounts_db", "accounts_app.ref", "synthetic-secret", "SELECT 1;")
        self.assertNotIn("synthetic-secret", str(caught.exception))
        self.assertIn("authentication failed", str(caught.exception))


class RuntimeGateTests(unittest.TestCase):
    def run_check(self, data, side_effect):
        output = io.StringIO()
        with patch.object(helper, "postgres", side_effect=side_effect) as postgres:
            with contextlib.redirect_stdout(output):
                result = helper.check(data)
        return result, output.getvalue(), postgres

    def test_all_five_correct_roles_and_ownership_pass(self):
        result, output, postgres = self.run_check(values(), runtime_response)
        self.assertTrue(result)
        self.assertEqual(postgres.call_count, 9)
        self.assertEqual(output.count("PASS"), 5)

    def test_admin_or_unqualified_roles_rejected_before_connecting(self):
        for username in ("postgres.accountsref", "accounts_app"):
            data = values()
            for service in helper.SERVICES:
                data[f"{service}_DB_USERNAME"] = username
            result, output, postgres = self.run_check(data, AssertionError("must not connect"))
            self.assertFalse(result)
            self.assertFalse(postgres.called)
            self.assertIn("forbidden", output)

    def test_wrong_project_suffix_rejected(self):
        data = values()
        data["ACCOUNTS_DB_USERNAME"] = "accounts_app.wrongproject"
        result, output, postgres = self.run_check(data, runtime_response)
        self.assertFalse(result)
        self.assertEqual(postgres.call_count, 7)
        self.assertIn("wrong Supabase project", output)

    def test_permission_tls_history_and_cross_service_denials_fail_closed(self):
        for field, invalid in (("tls", False), ("safe_role", False), ("owner", False),
                               ("schema", False), ("tables", False), ("sequences", False),
                               ("history", False), ("foreign_connect", True)):
            def response(_, host, database, username, *args):
                result = ready(database, username.split(".")[0])
                result[field] = invalid
                return result
            with self.subTest(field=field):
                result, output, _ = self.run_check(values(), response)
                self.assertFalse(result)
                self.assertNotIn("PASS", output)

    def test_actual_database_or_role_mapping_must_match(self):
        def response(_, host, database, username, *args):
            return ready("postgres", "postgres")
        result, output, _ = self.run_check(values(), response)
        self.assertFalse(result)
        self.assertIn("database/role mapping", output)

    def test_missing_or_failed_flyway_history_blocks_business_services(self):
        for history in ([], [{"version": "1", "script": "V1__synthetic.sql", "success": False}]):
            def response(*args):
                if "FROM public.flyway_schema_history" in args[5]:
                    return {"migrations": history}
                return runtime_response(*args)
            with self.subTest(history=history):
                result, output, _ = self.run_check(values(), response)
                self.assertFalse(result)
                self.assertEqual(output.count("Flyway history does not match"), 4)

    def test_failed_readiness_starts_no_container(self):
        output = io.StringIO()
        with patch.object(helper.sys, "argv", ["supabase.py", "start"]), \
             patch.object(helper, "read_env", return_value=values()), \
             patch.object(helper, "config", return_value={}), \
             patch.object(helper, "check", return_value=False), \
             patch.object(helper.subprocess, "run") as run, \
             contextlib.redirect_stdout(output):
            self.assertEqual(helper.main(), 1)
        self.assertFalse(run.called)
        self.assertIn("Nothing started", output.getvalue())


class ConfigurationGateTests(unittest.TestCase):
    @patch.object(helper.shutil, "which", return_value="/synthetic/docker")
    def test_local_database_or_its_dependency_is_rejected(self, _):
        for services in (
            {"accounts-postgres": {}},
            {"accounts-service": {"depends_on": {"accounts-postgres": {}}}},
        ):
            rendered = subprocess.CompletedProcess([], 0, json.dumps({"services": services}), "")
            with self.subTest(services=services), \
                 patch.object(helper.subprocess, "run", return_value=rendered), \
                 self.assertRaises(helper.CheckError):
                helper.config(values(), Path("/synthetic/.env"))

    @patch.object(helper.shutil, "which", return_value="/synthetic/docker")
    def test_configuration_output_and_diagnostics_are_secret_safe(self, _):
        rendered = subprocess.CompletedProcess([], 1, "synthetic-password", "synthetic-password")
        with patch.object(helper.subprocess, "run", return_value=rendered):
            with self.assertRaises(helper.CheckError) as caught:
                helper.config(values(), Path("/synthetic/.env"))
        self.assertNotIn("synthetic-password", str(caught.exception))

    @patch.object(helper.shutil, "which", return_value="/synthetic/docker")
    def test_ambient_profiles_cannot_activate_local_postgres(self, _):
        rendered = subprocess.CompletedProcess([], 0, json.dumps({"services": {"accounts-service": {}}}), "")
        output = io.StringIO()
        with patch.dict(helper.os.environ, {"COMPOSE_PROFILES": "local-postgres"}), \
             patch.object(helper.subprocess, "run", return_value=rendered) as run, \
             contextlib.redirect_stdout(output):
            env = helper.config(values(), Path("/synthetic/.env"))
        self.assertNotIn("COMPOSE_PROFILES", env)
        self.assertNotIn("COMPOSE_PROFILES", run.call_args.kwargs["env"])
        self.assertNotIn("synthetic-password-for-test-only", output.getvalue())


if __name__ == "__main__":
    unittest.main()
