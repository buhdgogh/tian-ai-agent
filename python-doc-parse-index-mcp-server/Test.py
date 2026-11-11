from mineru.client import MinerU

# 1. 初始化客户端
client = MinerU("")

# 2. 提交解析任务（参数名改为 model）
result = client.extract(
    "./data/96-3.pdf",
    model="vlm",              # 可选 "pipeline" / "vlm" / "html"
    extra_formats=["docx", "html"],
    table=True,               # 注意：参数名可能是 table，而不是 enable_table
    formula=True              # 参数名可能是 formula，而不是 enable_formula
)

# 3. 获取结果
if result.state == "done":
    print("Markdown 内容：", result.markdown[:500])
    for img in result.images:
        print(f"图片：{img.filename}")
    result.save_all("./mineru_output/")
else:
    print(f"解析失败：{result.error}")
