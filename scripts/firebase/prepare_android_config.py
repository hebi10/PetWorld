"""Materialize an isolated Android Firebase configuration for CI."""

import argparse
import base64
import binascii
import json
import os
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("variant", choices=("firebaseDev", "release"))
    parser.add_argument("--use-console-export", action="store_true")
    args = parser.parse_args()
    development = args.variant == "firebaseDev"
    project = "petworld-dev" if development else "petworld-prod-3b5c5"
    package = "com.haebi.petworld.dev" if development else "com.haebi.petworld"
    export = os.environ.get("PETWORLD_DEV_GOOGLE_SERVICES_JSON_BASE64", "")
    if args.use_console_export and not development:
        parser.error("The development console export can only be used with firebaseDev")

    if args.use_console_export and export:
        try:
            config = json.loads(base64.b64decode(export, validate=True))
            client = next(
                client for client in config["client"]
                if client["client_info"]["android_client_info"]["package_name"] == package
            )
            valid = config["project_info"]["project_id"] == project and any(
                oauth.get("client_type") == 3 and oauth.get("client_id")
                for oauth in client.get("oauth_client", [])
            )
        except (binascii.Error, ValueError, KeyError, TypeError, AttributeError, StopIteration):
            valid = False
        if not valid:
            raise SystemExit("Expected a petworld-dev Android config with a Google web OAuth client")
        source = "console-export"
    else:
        config = {
            "project_info": {"project_number": "1234567890", "project_id": project},
            "client": [{
                "client_info": {
                    "mobilesdk_app_id": "1:1234567890:android:0000000000000000",
                    "android_client_info": {"package_name": package},
                },
                "api_key": [{"current_key": "fake-ci-key"}],
            }],
            "configuration_version": "1",
        }
        source = "fixture"

    path = Path("app/src") / args.variant / "google-services.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(config), encoding="utf-8")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"source={source}\n")
    print(f"Prepared {args.variant} configuration from {source}")


if __name__ == "__main__":
    main()
