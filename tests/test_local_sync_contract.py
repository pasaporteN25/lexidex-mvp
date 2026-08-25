import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

from local_sync_contract import (  # noqa: E402
    MAX_SYNC_CHANGES,
    MAX_SYNC_PULL_LIMIT,
    MAX_SYNC_REQUEST_BYTES,
    SyncContractError,
    parse_error_response,
    parse_exchange_request,
    parse_exchange_response,
)


FIXTURES = ROOT / "contracts" / "local-sync" / "v1" / "fixtures"


class LocalSyncContractTest(unittest.TestCase):
    def fixture(self, name):
        return (FIXTURES / name).read_text(encoding="utf-8")

    def test_python_interprets_the_shared_request_fixture(self):
        request = parse_exchange_request(self.fixture("exchange-request.valid.json"))

        self.assertEqual(request["request_id"], "req_00000000000000000000000000000001")
        self.assertEqual(request["since_cursor"], "103")
        self.assertEqual(len(request["changes"]), 5)
        self.assertEqual(
            {change["entity_type"] for change in request["changes"]},
            {"personal_term", "favorite", "history", "collection", "collection_member"},
        )
        self.assertEqual(
            request["changes"][0]["entity_id"]["uid"],
            "usr_33333333333333333333333333333333",
        )

    def test_python_interprets_the_shared_response_and_error_fixtures(self):
        response = parse_exchange_response(self.fixture("exchange-response.valid.json"))
        error = parse_error_response(self.fixture("error-response.valid.json"))

        self.assertEqual(
            [item["status"] for item in response["acknowledgements"]],
            ["applied", "duplicate", "conflict"],
        )
        self.assertEqual([item["cursor"] for item in response["changes"]], ["104", "105"])
        self.assertEqual(response["next_cursor"], "105")
        self.assertEqual(error["error"]["code"], "cursor_expired")
        self.assertFalse(error["error"]["retryable"])

    def test_duplicate_change_id_is_rejected_with_the_same_stable_code(self):
        with self.assertRaises(SyncContractError) as caught:
            parse_exchange_request(
                self.fixture("exchange-request.invalid-duplicate-change-id.json")
            )

        self.assertEqual(caught.exception.code, "duplicate_change_id")

    def test_v1_limits_are_part_of_the_executable_contract(self):
        self.assertEqual(MAX_SYNC_REQUEST_BYTES, 1024 * 1024)
        self.assertEqual(MAX_SYNC_CHANGES, 200)
        self.assertEqual(MAX_SYNC_PULL_LIMIT, 200)


if __name__ == "__main__":
    unittest.main()
