# instance.py

from fastmcp import FastMCP

mcp = FastMCP(name="Local Knowledge Base")

# ========== 关键：导入工具模块，使装饰器生效 ==========
# 导入后，@mcp.tool() 才会执行
from mcp_server.tools import mineru_tools
from mcp_server.tools import llamaIndex_tools

