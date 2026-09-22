package com.tianaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.tianaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.nio.file.Path;

/**
 * 描述：资源下载工具
 */
public class ResourceDownloadTool {
    @Tool(description = "Download a resource from a given URL")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url, @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        try {
            Path filePath = SafePathResolver.resolve(fileDir, fileName);
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 使用 Hutool 得 downloadFile 方法下载资源
            HttpUtil.downloadFile(url, filePath.toFile());
            return "Resource downloaded successfully to: " + filePath;
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }
}
