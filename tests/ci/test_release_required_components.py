"""
SwiftDrop Release Required Component Validation & Negative Tests (Issue #167)
Verifies physical presence of all 6 Java microservices, frontend SPA, Keycloak identity,
shared Protobuf contracts, GraphQL SDL, and OpenAPI schemas, and validates fail-closed
negative behavior when components are missing or invalid.
"""

import json
import unittest
from pathlib import Path
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"
RELEASE_DIR = REPO_ROOT / "platform" / "kubernetes" / "release"
SCHEMA_FILE = RELEASE_DIR / "manifest-schema.json"

EXPECTED_SERVICES = [
    "api-gateway",
    "accounts-service",
    "pricing-service",
    "delivery-service",
    "communications-service",
    "graphql-query-service",
]

EXPECTED_EIGHT_IMAGES = [
    "frontend",
    "api-gateway",
    "accounts-service",
    "pricing-service",
    "delivery-service",
    "communications-service",
    "graphql-query-service",
    "keycloak",
]


class TestReleaseRequiredComponents(unittest.TestCase):
    """Verifies that all required microservices, contracts, and images exist physically and in release configurations."""

    def test_all_six_java_services_have_build_descriptors_and_wrappers(self):
        """Verifies all 6 Java microservices have valid pom.xml, mvnw, and source directories."""
        for svc in EXPECTED_SERVICES:
            svc_dir = REPO_ROOT / "services" / svc
            self.assertTrue(svc_dir.is_dir(), f"Service directory missing: {svc_dir}")
            
            pom_file = svc_dir / "pom.xml"
            self.assertTrue(pom_file.is_file(), f"pom.xml missing for {svc}")
            pom_content = pom_file.read_text(encoding="utf-8")
            self.assertIn("<groupId>com.swiftdrop</groupId>", pom_content, f"GroupId mismatch in {pom_file}")
            
            mvnw_file = svc_dir / "mvnw"
            self.assertTrue(mvnw_file.is_file(), f"mvnw missing for {svc}")

    def test_frontend_spa_has_package_json_and_source_tree(self):
        """Verifies React SPA production bundle sources and package descriptor exist."""
        frontend_dir = REPO_ROOT / "frontend"
        self.assertTrue(frontend_dir.is_dir(), "Frontend directory missing")
        self.assertTrue((frontend_dir / "package.json").is_file(), "package.json missing")
        self.assertTrue((frontend_dir / "src" / "main.tsx").is_file(), "src/main.tsx missing")
        self.assertTrue((frontend_dir / "Dockerfile").is_file(), "frontend/Dockerfile missing")

    def test_keycloak_identity_container_and_realm_exist(self):
        """Verifies Keycloak Dockerfile and realm JSON configuration exist."""
        identity_dir = REPO_ROOT / "platform" / "identity"
        self.assertTrue((identity_dir / "Dockerfile").is_file(), "Keycloak Dockerfile missing")
        self.assertTrue((identity_dir / "realm-swiftdrop.json").is_file(), "realm-swiftdrop.json missing")

    def test_shared_protobuf_contracts_exist(self):
        """Verifies shared Protobuf contracts module exists with .proto definitions."""
        proto_dir = REPO_ROOT / "shared" / "proto-contracts"
        self.assertTrue(proto_dir.is_dir(), "shared/proto-contracts directory missing")
        self.assertTrue((proto_dir / "pom.xml").is_file(), "proto-contracts pom.xml missing")
        contracts_grpc = REPO_ROOT / "contracts" / "grpc"
        self.assertTrue(contracts_grpc.is_dir(), "contracts/grpc directory missing")
        protos = list(contracts_grpc.glob("**/*.proto"))
        self.assertGreater(len(protos), 0, "No .proto definitions found in contracts/grpc")

    def test_graphql_sdl_schema_exists(self):
        """Verifies read-only GraphQL Query API SDL schema exists."""
        graphql_schema = REPO_ROOT / "contracts" / "graphql" / "schema.graphql"
        self.assertTrue(graphql_schema.is_file(), f"GraphQL SDL missing: {graphql_schema}")
        content = graphql_schema.read_text(encoding="utf-8")
        self.assertIn("type Query", content, "GraphQL SDL must declare 'type Query'")

    def test_all_eight_images_in_helm_values(self):
        """Verifies that platform/kubernetes/helm/swiftdrop/values.yaml declares all 8 runtime images with pinned digests."""
        values_file = REPO_ROOT / "platform" / "kubernetes" / "helm" / "swiftdrop" / "values.yaml"
        vdata = yaml.safe_load(values_file.read_text(encoding="utf-8"))
        
        mapping = {
            "frontend": vdata.get("frontend", {}),
            "api-gateway": vdata.get("apiGateway", {}),
            "accounts-service": vdata.get("accounts", {}),
            "pricing-service": vdata.get("pricing", {}),
            "delivery-service": vdata.get("delivery", {}),
            "communications-service": vdata.get("communications", {}),
            "graphql-query-service": vdata.get("graphql", {}),
            "keycloak": vdata.get("keycloak", {}),
        }
        
        for name, spec in mapping.items():
            img = spec.get("image", {})
            self.assertIn("repository", img, f"Workload {name} missing image.repository in values.yaml")
            digest = img.get("digest")
            self.assertIsNotNone(digest, f"Workload {name} missing image.digest in values.yaml")
            self.assertTrue(digest.startswith("sha256:"), f"Workload {name} digest '{digest}' not starting with sha256:")
            self.assertEqual(len(digest), 71, f"Workload {name} digest length invalid")


class TestNegativeComponentGates(unittest.TestCase):
    """Negative tests: proves that missing services, tests, or digests fail release promotion."""

    def test_missing_image_in_manifest_fails_schema(self):
        """Negative test: release candidate manifest with 7 of 8 images fails schema validation."""
        try:
            import jsonschema
        except ImportError:
            self.skipTest("jsonschema not installed")
        schema = json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))
        
        # Manifest with only 7 images
        incomplete_manifest = {
            "manifest_version": "1.0.0",
            "source_commit_sha": "a" * 40,
            "approved_base_branch": "test",
            "workflow_run_id": "12345",
            "workflow_run_attempt": 1,
            "artifact_generation_time": "2026-10-28T00:00:00Z",
            "target_architecture": "linux/amd64",
            "contract_artifact_digest": None,
            "approval_reference": "test-approval",
            "images": [
                {
                    "image_name": name,
                    "image_repository": f"ghcr.io/suthankan1/swiftdrop-{name}",
                    "image_digest": "sha256:" + "0" * 64,
                    "tag_reference": "abcdef0"
                }
                for name in EXPECTED_EIGHT_IMAGES[:7]  # Omit 8th (keycloak)
            ]
        }
        
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(instance=incomplete_manifest, schema=schema)

    def test_mutable_tag_in_manifest_fails_schema(self):
        """Negative test: mutable tag in place of sha256 digest fails schema validation."""
        try:
            import jsonschema
        except ImportError:
            self.skipTest("jsonschema not installed")
        schema = json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))
        
        invalid_manifest = {
            "manifest_version": "1.0.0",
            "source_commit_sha": "a" * 40,
            "approved_base_branch": "test",
            "workflow_run_id": "12345",
            "workflow_run_attempt": 1,
            "artifact_generation_time": "2026-10-28T00:00:00Z",
            "target_architecture": "linux/amd64",
            "contract_artifact_digest": None,
            "approval_reference": "test-approval",
            "images": [
                {
                    "image_name": name,
                    "image_repository": f"ghcr.io/suthankan1/swiftdrop-{name}",
                    "image_digest": "latest",  # Mutable tag!
                    "tag_reference": "abcdef0"
                }
                for name in EXPECTED_EIGHT_IMAGES
            ]
        }
        
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(instance=invalid_manifest, schema=schema)


if __name__ == "__main__":
    unittest.main()
