"""Validate dev/prod Firebase Android config segregation without printing credentials.

Actual configs must match the registered Firebase projects and Android package.
Synthetic CI fixtures must be explicitly identified as demo projects.
"""

import argparse
import json
from pathlib import Path

PROFILES = {
    "dev": {
        "project": "petworld-dev",
        "demo_project": "demo-petworld-fb01",
        "package": "com.haebi.petworld.dev",
        "path": Path("app/src/firebaseDev/google-services.json"),
    },
    "prod": {
        "project": "petworld-prod-3b5c5",
        "demo_project": "demo-petworld-prod",
        "package": "com.haebi.petworld",
        "path": Path("app/src/release/google-services.json"),
    },
}


def validate_config(data: dict, environment: str, *, demo: bool = False) -> None:
    spec = PROFILES[environment]
    expected_project = spec["demo_project"] if demo else spec["project"]
    project_info = data.get("project_info")
    if not isinstance(project_info, dict) or project_info.get("project_id") != expected_project:
        raise ValueError(f"{environment}: unexpected Firebase project")
    clients = data.get("client")
    if not isinstance(clients, list):
        raise ValueError(f"{environment}: Android client list is missing")
    matching = [
        client for client in clients
        if isinstance(client, dict)
        and isinstance(client.get("client_info"), dict)
        and client["client_info"].get("android_client_info", {}).get("package_name") == spec["package"]
    ]
    if len(matching) != 1:
        raise ValueError(f"{environment}: expected exactly one registered Android package")
    client = matching[0]
    if not isinstance(client["client_info"].get("mobilesdk_app_id"), str) or not client["client_info"]["mobilesdk_app_id"]:
        raise ValueError(f"{environment}: Firebase Android App ID is missing")
    oauth_clients = client.get("oauth_client", [])
    if not isinstance(oauth_clients, list) or not any(
        isinstance(oauth, dict) and oauth.get("client_type") == 3
        and isinstance(oauth.get("client_id"), str) and oauth["client_id"]
        for oauth in oauth_clients
    ):
        raise ValueError(f"{environment}: Google sign-in web OAuth client is missing")
    if len(client.get("api_key", [])) == 0:
        raise ValueError(f"{environment}: Firebase Android API key is missing")


def validate_paths(root: Path) -> None:
    for path in ("app/google-services.json", "app/src/main/google-services.json"):
        if (root / path).exists():
            raise ValueError("A shared google-services.json would mix Firebase build environments")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--dev", type=Path)
    parser.add_argument("--prod", type=Path)
    parser.add_argument("--ci-demo", action="store_true")
    args = parser.parse_args()
    if not args.dev and not args.prod:
        parser.error("Specify --dev and/or --prod")
    validate_paths(args.root)
    for name, path in (("dev", args.dev), ("prod", args.prod)):
        if path is None:
            continue
        with path.open(encoding="utf-8") as config_file:
            data = json.load(config_file)
        validate_config(data, name, demo=args.ci_demo)
        print(f"PASS: {name} Firebase package, project and web OAuth client match the expected environment")
    print("No Firebase API key, client ID, private credential, token or signing certificate was printed.")


if __name__ == "__main__":
    main()
