import json
import os
import pathlib
import sys
import tempfile
import textwrap
import unittest
from unittest import mock

from sheet_analyzer import model
from sheet_analyzer.config import Config
from sheet_analyzer.errors import Failure

FAKE_CLAUDE = textwrap.dedent("""\
    #!{python}
    import json, os, sys, time
    record = dict(argv=sys.argv[1:], cwd=os.getcwd(), cwd_files=os.listdir("."), stdin=sys.stdin.read(),
                  https_proxy=os.environ.get("HTTPS_PROXY"), claudecode=os.environ.get("CLAUDECODE"),
                  telemetry=os.environ.get("DISABLE_TELEMETRY"))
    with open(os.environ["FAKE_CLAUDE_RECORD"], "w") as f:
        json.dump(record, f)
    mode = os.environ.get("FAKE_CLAUDE_MODE", "fenced")
    if mode == "sleep":
        time.sleep(30)
    if mode == "error":
        print(json.dumps(dict(is_error=True, result="budget exceeded")))
        sys.exit(1)
    if mode == "garbage":
        print("not json")
        sys.exit(0)
    result = '```json\\n{{"workbook_key": "k1", "routes": []}}\\n```' if mode == "fenced" else "[1, 2]"
    print(json.dumps(dict(is_error=False, result=result)))
""")


class CommandTests(unittest.TestCase):
    def test_exact_isolation_flags(self):
        config = Config(claude="/home/u/.local/bin/claude")
        self.assertEqual(model.command(config), [
            "/home/u/.local/bin/claude", "-p", "--model", "claude-sonnet-5-5",
            "--safe-mode", "--restricted", "--tools", "", "--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}',
            "--disable-slash-commands", "--no-chrome", "--setting-sources", "", "--no-session-persistence",
            "--effort", "low", "--system-prompt", model.SYSTEM_PROMPT,
            "--output-format", "json", "--max-budget-usd", "5",
        ])

    def test_model_effort_budget_are_configurable(self):
        config = Config.from_env(dict(SHEET_ANALYZER_MODEL="m", SHEET_ANALYZER_EFFORT="medium", SHEET_ANALYZER_BUDGET_USD="2"))
        command = model.command(config)
        self.assertEqual(command[command.index("--model") + 1], "m")
        self.assertEqual(command[command.index("--effort") + 1], "medium")
        self.assertEqual(command[command.index("--max-budget-usd") + 1], "2")

    def test_environment_sets_proxy_and_drops_nesting_marker(self):
        env = model.environment(Config(), dict(CLAUDECODE="1", HOME="/home/u"))
        self.assertEqual(env["HTTPS_PROXY"], "http://127.0.0.1:3181")
        self.assertEqual(env["HTTP_PROXY"], "http://127.0.0.1:3181")
        self.assertNotIn("CLAUDECODE", env)
        self.assertEqual(env["HOME"], "/home/u")
        self.assertNotIn("HTTPS_PROXY", model.environment(Config(proxy=""), {}))

    def test_prompt_is_the_clean_experiment_prompt(self):
        self.assertTrue(model.PROMPT.startswith("You describe how an app can READ a student's points"))
        self.assertTrue(model.PROMPT.endswith("WORKBOOK JSON follows:\n"))


class RunTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.binary = pathlib.Path(self.dir.name) / "claude"
        self.binary.write_text(FAKE_CLAUDE.format(python=sys.executable))
        self.binary.chmod(0o755)
        self.record = pathlib.Path(self.dir.name) / "record.json"
        self.config = Config(claude=str(self.binary), model_timeout=5)

    def tearDown(self):
        self.dir.cleanup()

    def run_fake(self, mode, config=None):
        with mock.patch.dict(os.environ, FAKE_CLAUDE_RECORD=str(self.record), FAKE_CLAUDE_MODE=mode, CLAUDECODE="1"):
            return model.run(config or self.config, '{"workbook_key":"k1"}\n')

    def test_fenced_answer_is_parsed_and_input_goes_on_stdin_from_an_empty_temp_cwd(self):
        self.assertEqual(self.run_fake("fenced"), {"workbook_key": "k1", "routes": []})
        record = json.loads(self.record.read_text())
        self.assertEqual(record["argv"], model.command(self.config)[1:])
        self.assertEqual(record["stdin"], model.PROMPT + '{"workbook_key":"k1"}\n')
        self.assertEqual(record["cwd_files"], [])
        self.assertFalse(os.path.exists(record["cwd"]))
        self.assertEqual(record["https_proxy"], "http://127.0.0.1:3181")
        self.assertIsNone(record["claudecode"])
        self.assertEqual(record["telemetry"], "1")

    def assertFailure(self, mode, code, config=None):
        with self.assertRaises(Failure) as error:
            self.run_fake(mode, config)
        self.assertEqual((error.exception.status, error.exception.code), ("model_failed", code))

    def test_cli_error_envelope(self):
        self.assertFailure("error", "cli_error")

    def test_garbage_output(self):
        self.assertFailure("garbage", "bad_envelope")

    def test_non_object_answer(self):
        self.assertFailure("array", "bad_json")

    def test_timeout_kills_the_cli(self):
        config = Config(claude=str(self.binary), model_timeout=1)
        self.assertFailure("sleep", "timeout", config)

    def test_missing_binary(self):
        self.assertFailure("fenced", "cli_missing", Config(claude=str(self.binary) + "-missing"))


class ParseTests(unittest.TestCase):
    def test_plain_and_fenced(self):
        for result in ('{"a": 1}', '```\n{"a": 1}\n```', '```json {"a": 1}```'):
            with self.subTest(result=result):
                self.assertEqual(model.parse_answer(json.dumps(dict(is_error=False, result=result))), {"a": 1})


if __name__ == "__main__":
    unittest.main()
