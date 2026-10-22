"""Validate OpenAPI YAML contracts for syntax and essential structure."""
from pathlib import Path
import yaml


def test_openapi_yaml_contracts_valid():
    contracts_dir = Path(__file__).resolve().parent.parent.parent / "docs" / "api-contracts"
    yaml_files = list(contracts_dir.glob("*.yaml"))
    assert len(yaml_files) >= 2, f"Expected at least 2 OpenAPI specs in {contracts_dir}"

    for spec_path in yaml_files:
        with open(spec_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        assert isinstance(data, dict), f"{spec_path.name} did not parse into a dict"
        assert "openapi" in data, f"{spec_path.name} missing 'openapi' version"
        assert "info" in data, f"{spec_path.name} missing 'info' block"
        assert "paths" in data, f"{spec_path.name} missing 'paths' block"
