#!/usr/bin/env python3
"""
Generates deterministic synthetic Terraform plan JSON fixtures for the SwiftDrop
strict $0 policy test suite.
"""

import json
from pathlib import Path

FIXTURES_DIR = Path(__file__).parent / "fixtures"
FIXTURES_DIR.mkdir(parents=True, exist_ok=True)


def base_plan(resource_changes=None, planned_resources=None, backend=None, variables=None, config_resources=None):
    plan = {
        "format_version": "1.2",
        "terraform_version": "1.16.3",
        "variables": variables or {"enable_azure_deployment": {"value": False}},
        "planned_values": {
            "root_module": {
                "resources": planned_resources or []
            }
        },
        "resource_changes": resource_changes or [],
        "configuration": {
            "provider_config": {
                "azurerm": {
                    "name": "azurerm",
                    "full_name": "registry.terraform.io/hashicorp/azurerm",
                    "version_constraint": "~> 4.0"
                }
            },
            "root_module": {
                "resources": config_resources or []
            }
        }
    }
    if backend:
        plan["configuration"]["backend"] = backend
    return plan


def create_rc(address, r_type, provider="registry.terraform.io/hashicorp/azurerm", actions=None, after=None, after_unknown=None, mode="managed"):
    return {
        "address": address,
        "mode": mode,
        "type": r_type,
        "name": address.split(".")[-1].split("[")[0],
        "provider_name": provider,
        "change": {
            "actions": actions or ["create"],
            "before": None,
            "after": after or {},
            "after_unknown": after_unknown or {}
        }
    }


def write_fixture(name, data):
    path = FIXTURES_DIR / name
    if isinstance(data, (dict, list)):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    else:
        with open(path, "w", encoding="utf-8") as f:
            f.write(data)
    print(f"Wrote fixture: {path.name}")


def main():
    # Positive fixtures
    write_fixture("empty-plan.json", base_plan())

    local_rc = create_rc(
        address="local_file.k3d_manifest",
        r_type="local_file",
        provider="registry.terraform.io/hashicorp/local",
        after={"filename": "cluster.yaml", "content": "k3d config"}
    )
    write_fixture("local-only-plan.json", base_plan(resource_changes=[local_rc]))

    noop_plan = base_plan()
    noop_plan["planned_values"]["outputs"] = {
        "hosting_target": {"value": "local-k3d-k3s"},
        "azure_deployment_authorized": {"value": False}
    }
    write_fixture("noop-root-module-plan.json", noop_plan)

    # Negative fixtures
    # 1. Azure VM
    vm_rc = create_rc(
        address="azurerm_linux_virtual_machine.backend_node",
        r_type="azurerm_linux_virtual_machine",
        after={"name": "swiftdrop-vm", "location": "eastus2", "size": "Standard_B2s"}
    )
    write_fixture("azure-vm.json", base_plan(resource_changes=[vm_rc]))

    # 2. Azure Managed Disk
    disk_rc = create_rc(
        address="azurerm_managed_disk.postgres_data",
        r_type="azurerm_managed_disk",
        after={"name": "pgdata-disk", "location": "eastus2", "storage_account_type": "Premium_LRS"}
    )
    write_fixture("azure-managed-disk.json", base_plan(resource_changes=[disk_rc]))

    # 3. Azure Public IP
    pip_rc = create_rc(
        address="azurerm_public_ip.gateway_ip",
        r_type="azurerm_public_ip",
        after={"name": "swiftdrop-pip", "location": "eastus2", "allocation_method": "Static", "sku": "Standard"}
    )
    write_fixture("azure-public-ip.json", base_plan(resource_changes=[pip_rc]))

    # 4. Azure NAT Gateway
    nat_rc = create_rc(
        address="azurerm_nat_gateway.egress_nat",
        r_type="azurerm_nat_gateway",
        after={"name": "swiftdrop-nat", "location": "eastus2", "sku_name": "Standard"}
    )
    write_fixture("azure-nat-gateway.json", base_plan(resource_changes=[nat_rc]))

    # 5. Azure Load Balancer
    lb_rc = create_rc(
        address="azurerm_lb.ingress_lb",
        r_type="azurerm_lb",
        after={"name": "swiftdrop-lb", "location": "eastus2", "sku": "Standard"}
    )
    write_fixture("azure-load-balancer.json", base_plan(resource_changes=[lb_rc]))

    # 6. AKS Cluster
    aks_rc = create_rc(
        address="azurerm_kubernetes_cluster.k8s_cluster",
        r_type="azurerm_kubernetes_cluster",
        after={"name": "swiftdrop-aks", "location": "eastus2", "dns_prefix": "swiftdrop"}
    )
    write_fixture("azure-aks-cluster.json", base_plan(resource_changes=[aks_rc]))

    # 7. Azure Storage Account
    storage_rc = create_rc(
        address="azurerm_storage_account.blob_storage",
        r_type="azurerm_storage_account",
        after={"name": "swiftdropstore", "location": "eastus2", "account_tier": "Standard"}
    )
    write_fixture("azure-storage-account.json", base_plan(resource_changes=[storage_rc]))

    # 8. Azure Storage Backend for remote state
    write_fixture("azure-backend-storage.json", base_plan(backend={"type": "azurerm", "config": {"storage_account_name": "tfstate"}}))

    # 9. Azure Functions
    func_rc = create_rc(
        address="azurerm_linux_function_app.notification_func",
        r_type="azurerm_linux_function_app",
        after={"name": "swiftdrop-func", "location": "eastus2"}
    )
    write_fixture("azure-functions.json", base_plan(resource_changes=[func_rc]))

    # 10. Azure Static Web App
    swa_rc = create_rc(
        address="azurerm_static_web_app.frontend_site",
        r_type="azurerm_static_web_app",
        after={"name": "swiftdrop-frontend", "location": "eastus2", "sku_tier": "Standard"}
    )
    write_fixture("azure-static-web-app.json", base_plan(resource_changes=[swa_rc]))

    # 11. Paid Azure Add-on (Log Analytics / Monitor / Firewall)
    addon_rc = create_rc(
        address="azurerm_log_analytics_workspace.monitoring",
        r_type="azurerm_log_analytics_workspace",
        after={"name": "swiftdrop-logs", "location": "eastus2", "sku": "PerGB2018"}
    )
    write_fixture("azure-paid-addon.json", base_plan(resource_changes=[addon_rc]))

    # 12. Unknown Azure Resource
    unknown_rc = create_rc(
        address="azurerm_quantum_workspace.experimental",
        r_type="azurerm_quantum_workspace",
        after={"name": "swiftdrop-quantum", "location": "eastus2"}
    )
    write_fixture("azure-unknown-resource.json", base_plan(resource_changes=[unknown_rc]))

    # 13. Nested Module Resource
    nested_rc = create_rc(
        address="module.platform_compute.module.workers.azurerm_linux_virtual_machine.worker_node",
        r_type="azurerm_linux_virtual_machine",
        after={"name": "worker-vm", "location": "eastus2", "size": "Standard_B1s"}
    )
    nested_plan = base_plan(resource_changes=[nested_rc])
    nested_plan["planned_values"]["root_module"]["child_modules"] = [
        {
            "address": "module.platform_compute",
            "child_modules": [
                {
                    "address": "module.platform_compute.module.workers",
                    "resources": [
                        {
                            "address": "module.platform_compute.module.workers.azurerm_linux_virtual_machine.worker_node",
                            "mode": "managed",
                            "type": "azurerm_linux_virtual_machine",
                            "name": "worker_node",
                            "provider_name": "registry.terraform.io/hashicorp/azurerm",
                            "values": {"name": "worker-vm", "location": "eastus2"}
                        }
                    ]
                }
            ]
        }
    ]
    write_fixture("azure-nested-module.json", nested_plan)

    # 14. Provider Alias
    alias_rc = create_rc(
        address="azurerm_virtual_network.secondary_vnet",
        r_type="azurerm_virtual_network",
        provider="provider[\"registry.terraform.io/hashicorp/azurerm\"].secondary",
        after={"name": "sec-vnet", "location": "centralus"}
    )
    write_fixture("azure-provider-alias.json", base_plan(resource_changes=[alias_rc]))

    # 15. Multiple instances
    inst0_rc = create_rc(
        address="azurerm_linux_virtual_machine.cluster[0]",
        r_type="azurerm_linux_virtual_machine",
        after={"name": "node-0", "location": "eastus2"}
    )
    inst1_rc = create_rc(
        address="azurerm_linux_virtual_machine.cluster[1]",
        r_type="azurerm_linux_virtual_machine",
        after={"name": "node-1", "location": "eastus2"}
    )
    write_fixture("azure-multiple-instances.json", base_plan(resource_changes=[inst0_rc, inst1_rc]))

    # 16. Unknown region
    unk_region_rc = create_rc(
        address="azurerm_resource_group.rg",
        r_type="azurerm_resource_group",
        after={"name": "swiftdrop-rg"},
        after_unknown={"location": True}
    )
    write_fixture("azure-unknown-region.json", base_plan(resource_changes=[unk_region_rc]))

    # 17. Unknown SKU
    unk_sku_rc = create_rc(
        address="azurerm_linux_virtual_machine.dynamic_vm",
        r_type="azurerm_linux_virtual_machine",
        after={"name": "dynamic-vm", "location": "eastus2"},
        after_unknown={"size": True}
    )
    write_fixture("azure-unknown-sku.json", base_plan(resource_changes=[unk_sku_rc]))

    # 18. Unapproved SKU
    unapp_sku_rc = create_rc(
        address="azurerm_linux_virtual_machine.heavy_vm",
        r_type="azurerm_linux_virtual_machine",
        after={"name": "heavy-vm", "location": "eastus2", "size": "Standard_E64s_v5"}
    )
    write_fixture("azure-unapproved-sku.json", base_plan(resource_changes=[unapp_sku_rc]))

    # 19. Unapproved Region
    unapp_reg_rc = create_rc(
        address="azurerm_resource_group.foreign_rg",
        r_type="azurerm_resource_group",
        after={"name": "foreign-rg", "location": "australiasoutheast"}
    )
    write_fixture("azure-unapproved-region.json", base_plan(resource_changes=[unapp_reg_rc]))

    # 20. Expired Evidence
    expired_rc = create_rc(
        address="azurerm_resource_group.stale_evidence",
        r_type="azurerm_resource_group",
        after={"name": "stale-rg", "location": "eastus2", "tags": {"pricing_evidence_expiry": "2024-01-01"}}
    )
    write_fixture("azure-expired-evidence.json", base_plan(resource_changes=[expired_rc]))

    # 21. Nonzero meter
    meter_rc = create_rc(
        address="azurerm_resource_group.metered_rg",
        r_type="azurerm_resource_group",
        after={"name": "metered-rg", "location": "eastus2", "tags": {"pricing_meter_type": "metered"}}
    )
    write_fixture("azure-nonzero-meter.json", base_plan(resource_changes=[meter_rc]))

    # 22. Promotional credit claim
    credit_rc = create_rc(
        address="azurerm_linux_virtual_machine.student_vm",
        r_type="azurerm_linux_virtual_machine",
        after={"name": "student-vm", "location": "eastus2", "size": "Standard_B1s", "tags": {"credit_offset": "students_promotional_credit"}}
    )
    write_fixture("azure-promotional-credit.json", base_plan(resource_changes=[credit_rc]))

    # 23. Unapproved subscription
    unapp_sub_rc = create_rc(
        address="azurerm_resource_group.rogue_sub",
        r_type="azurerm_resource_group",
        after={"name": "rogue-rg", "location": "eastus2"}
    )
    write_fixture("azure-unapproved-subscription.json", base_plan(resource_changes=[unapp_sub_rc]))

    # 24. Unknown planned resource values
    unk_values_rc = create_rc(
        address="azurerm_storage_account.dynamic_store",
        r_type="azurerm_storage_account",
        after={"name": "dynstore"},
        after_unknown={"location": True, "account_tier": True}
    )
    write_fixture("azure-unknown-planned-values.json", base_plan(resource_changes=[unk_values_rc]))

    # 25. Malformed plan JSON
    write_fixture("malformed-plan.json", "{\"incomplete_json\": [true, ")

    # 26. Corrupted allowlist YAML
    write_fixture("corrupted-allowlist.yaml", "version: '1.0'\nazure: [unclosed list\n  - item")

    # 27. Missing deployment authorization field in policy
    write_fixture("missing-deployment-auth.yaml", "version: '1.0'\npolicy_mode: 'deny-by-default'\ncost_ceiling_usd: 0.00\nazure:\n  allowed_resources: []\n")

    # 28. Unapproved default-off override attempt
    override_plan = base_plan(
        variables={"enable_azure_deployment": {"value": True}},
        resource_changes=[vm_rc]
    )
    write_fixture("unapproved-override.json", override_plan)

    print("Deterministic test fixtures generated successfully.")


if __name__ == "__main__":
    main()
