"""Validate CI client configuration without printing any supplied values."""
import json
import os
from pathlib import Path
import sys


def validate(raw, project):
    if not raw or not project:
        raise ValueError("Firebase config secret and project variable are required")
    try:
        config = json.loads(raw)
        if not isinstance(config, dict):
            raise ValueError()
        if any(key in config for key in ("private_key", "private_key_id", "client_email")):
            raise ValueError()
        if config.get("type") == "service_account":
            raise ValueError()
        if config["project_info"]["project_id"] != project:
            raise ValueError()
        clients = [client for client in config["client"] if
                   client["client_info"]["android_client_info"]["package_name"]
                   == "com.alad1nks.jaiqal"]
        if len(clients) != 1:
            raise ValueError()
        client = clients[0]
        if not client["client_info"]["mobilesdk_app_id"]:
            raise ValueError()
        if not any(key.get("current_key") for key in client["api_key"]):
            raise ValueError()
        if not any(oauth.get("client_type") == 3 and oauth.get("client_id")
                   for oauth in client["oauth_client"]):
            raise ValueError()
    except (ValueError, KeyError, TypeError, AttributeError):
        raise ValueError("Expected Android client config for the selected project, package and Google Web OAuth client; Admin credentials are forbidden") from None
    return config


def main():
    try:
        config = validate(os.environ.get("FIREBASE_ANDROID_CONFIG_JSON", ""),
                          os.environ.get("FIREBASE_PROJECT_ID", ""))
        target = Path(__file__).resolve().parents[1] / "app/androidApp/google-services.json"
        # Refuse to overwrite any existing local configuration.
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(config, output)
            output.write("\n")
    except ValueError as failure:
        print(str(failure), file=sys.stderr)
        return 1
    except OSError:
        print("Cannot create Android configuration; destination must not exist", file=sys.stderr)
        return 1
    print("Android Firebase configuration validated and materialized")
    return 0


if __name__ == "__main__":
    sys.exit(main())
