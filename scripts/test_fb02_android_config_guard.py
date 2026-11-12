import unittest

from firebase.android_config_guard import validate_config


def config(project, package, web=True):
    return {
        "project_info": {"project_id": project, "project_number": "1234567890"},
        "client": [{
            "client_info": {
                "mobilesdk_app_id": "1:1234567890:android:fake",
                "android_client_info": {"package_name": package},
            },
            "api_key": [{"current_key": "fixture-not-a-real-key"}],
            "oauth_client": (
                [{"client_id": "fixture-web-client", "client_type": 3}] if web else []
            ),
        }],
    }


class FirebaseConfigGuardTest(unittest.TestCase):
    def test_valid_dev_and_prod_configs(self):
        validate_config(config("petworld-dev", "com.haebi.petworld.dev"), "dev")
        validate_config(config("petworld-prod-3b5c5", "com.haebi.petworld"), "prod")

    def test_correct_demo_fixtures(self):
        validate_config(config("demo-petworld-fb01", "com.haebi.petworld.dev"), "dev", demo=True)
        validate_config(config("demo-petworld-prod", "com.haebi.petworld"), "prod", demo=True)

    def test_dev_config_cannot_be_used_in_production(self):
        with self.assertRaisesRegex(ValueError, "unexpected Firebase project"):
            validate_config(config("petworld-dev", "com.haebi.petworld.dev"), "prod")

    def test_invalid_android_package_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Android package"):
            validate_config(config("petworld-dev", "com.haebi.petworld"), "dev")

    def test_stale_config_without_web_oauth_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "web OAuth"):
            validate_config(config("petworld-dev", "com.haebi.petworld.dev", False), "dev")

    def test_multiple_package_clients_are_rejected(self):
        settings = config("petworld-dev", "com.haebi.petworld.dev")
        settings["client"].append(settings["client"][0])
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_config(settings, "dev")

    def test_demo_config_is_never_accepted_as_actual(self):
        with self.assertRaisesRegex(ValueError, "unexpected Firebase project"):
            validate_config(config("demo-petworld-fb01", "com.haebi.petworld.dev"), "dev")


if __name__ == "__main__":
    unittest.main()
