import dataclasses
import os
import pathlib

DEFAULT_SOCKET = "/srv/data/sheet-analyzer/run/analyzer.sock"


@dataclasses.dataclass(frozen=True)
class Config:
    socket_path: str = DEFAULT_SOCKET
    claude: str = str(pathlib.Path.home() / ".local/bin/claude")
    model: str = "claude-sonnet-5-5"
    effort: str = "low"
    budget_usd: str = "5"
    proxy: str = "http://127.0.0.1:3181"  # empty: no proxy variables for the CLI
    model_timeout: int = 420
    max_input_bytes: int = 1_600_000  # the largest experiment input that fit the model context was 1.47 MB
    io_timeout: int = 10

    @classmethod
    def from_env(cls, env=None):
        env = os.environ if env is None else env
        base = cls()
        return cls(
            socket_path=env.get("SHEET_ANALYZER_SOCKET", base.socket_path),
            claude=env.get("SHEET_ANALYZER_CLAUDE", base.claude),
            model=env.get("SHEET_ANALYZER_MODEL", base.model),
            effort=env.get("SHEET_ANALYZER_EFFORT", base.effort),
            budget_usd=env.get("SHEET_ANALYZER_BUDGET_USD", base.budget_usd),
            proxy=env.get("SHEET_ANALYZER_PROXY", base.proxy),
            model_timeout=int(env.get("SHEET_ANALYZER_MODEL_TIMEOUT", base.model_timeout)),
            max_input_bytes=int(env.get("SHEET_ANALYZER_MAX_INPUT_BYTES", base.max_input_bytes)),
        )
