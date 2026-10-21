import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "firebase_config", Path(__file__).with_name("materialize-firebase-android.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FirebaseConfigTest(unittest.TestCase):
    def config(self):
        return {"project_info": {"project_id": "test-project"}, "client": [{
            "client_info": {"mobilesdk_app_id": "fake-app-id",
                            "android_client_info": {"package_name": "com.alad1nks.jaiqal"}},
            "api_key": [{"current_key": "fake-client-key"}],
            "oauth_client": [{"client_type": 3, "client_id": "fake-web-client"}],
        }]}

    def test_accepts_client_config(self):
        config = self.config()
        self.assertEqual(config, module.validate(json.dumps(config), "test-project"))

    def test_rejects_invalid_shapes_without_echoing_input(self):
        for raw in ("", "private-marker", "[]", "null", '{"client":null}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError) as failure:
                module.validate(raw, "test-project")
            self.assertNotIn("private-marker", str(failure.exception))

    def test_rejects_wrong_project_package_missing_oauth_and_admin_key(self):
        for case in ("project", "package", "oauth", "admin", "duplicate"):
            config = self.config()
            if case == "project":
                config["project_info"]["project_id"] = "other-project"
            elif case == "package":
                config["client"][0]["client_info"]["android_client_info"]["package_name"] = "other.package"
            elif case == "oauth":
                config["client"][0]["oauth_client"] = [{"client_type": 1, "client_id": "android-only"}]
            elif case == "admin":
                config["private_key"] = "private-marker"
            else:
                config["client"].append(config["client"][0])
            with self.subTest(case=case), self.assertRaises(ValueError) as failure:
                module.validate(json.dumps(config), "test-project")
            self.assertNotIn("private-marker", str(failure.exception))


if __name__ == "__main__":
    unittest.main()
