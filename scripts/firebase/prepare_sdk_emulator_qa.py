"""Prepare a disposable FirebaseDev APK for local Android emulator QA only."""
import json
import os
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
ROOT = Path("app/src/firebaseDev")

def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("PETWORLD_LOCAL_SDK_QA") != "true":
        raise SystemExit("This script is restricted to disposable local-SDK CI checkout")
    config = json.loads((ROOT / "google-services.json").read_text(encoding="utf-8"))
    if config["project_info"] != {"project_number": "1234567890", "project_id": "petworld-dev"}:
        raise SystemExit("Expected only the synthetic development fixture")
    clients = config.get("client", [])
    if (len(clients) != 1 or clients[0].get("api_key") != [{"current_key": "fake-ci-key"}]
            or clients[0]["client_info"]["android_client_info"]["package_name"] != "com.haebi.petworld.dev"
            or clients[0].get("oauth_client")):
        raise SystemExit("Real Firebase credentials must not be used in local SDK QA")
    manifest = ROOT / "AndroidManifest.xml"
    ET.register_namespace("android", ANDROID)
    tree = ET.parse(manifest)
    app = tree.getroot().find("application")
    if app is None or app.get(f"{{{ANDROID}}}networkSecurityConfig"):
        raise SystemExit("Unexpected development manifest")
    res = ROOT / "res/xml/petworld_sdk_qa_network.xml"
    if res.exists():
        raise SystemExit("Unexpected existing test network configuration")
    res.parent.mkdir(parents=True, exist_ok=True)
    res.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<network-security-config>\n'
        '  <base-config cleartextTrafficPermitted="false"/>\n'
        '  <domain-config cleartextTrafficPermitted="true">\n'
        '    <domain includeSubdomains="false">10.0.2.2</domain>\n'
        '  </domain-config>\n'
        '</network-security-config>\n',
        encoding="utf-8",
    )
    app.set(f"{{{ANDROID}}}networkSecurityConfig", "@xml/petworld_sdk_qa_network")
    tree.write(manifest, encoding="utf-8", xml_declaration=True)

if __name__ == "__main__":
    main()
