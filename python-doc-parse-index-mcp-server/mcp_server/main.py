import logging
import os

from dotenv import load_dotenv

load_dotenv()
os.environ.setdefault("FASTMCP_CHECK_FOR_UPDATES", "off")

from mcp_server.instance import mcp  # noqa: E402
from mcp_server.storage import knowledge_store  # noqa: E402


def main() -> None:
    logging.basicConfig(
        level=getattr(logging, os.getenv("MCP_LOG_LEVEL", "INFO").upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    migrated_tasks = knowledge_store.migrate_legacy_tasks()
    if migrated_tasks:
        logging.getLogger(__name__).info("已迁移 %d 个旧解析任务到 SQLite", migrated_tasks)
    mcp.run(
        transport="streamable-http",
        host=os.getenv("MCP_HOST", "127.0.0.1"),
        port=int(os.getenv("MCP_PORT", "8081")),
        path=os.getenv("MCP_PATH", "/mcp"),
        log_level=os.getenv("MCP_LOG_LEVEL", "info"),
    )


if __name__ == "__main__":
    main()
