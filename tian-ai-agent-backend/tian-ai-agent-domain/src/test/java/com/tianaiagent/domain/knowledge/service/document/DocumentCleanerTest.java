package com.tianaiagent.domain.knowledge.service.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentCleanerTest {
    @Test
    void removesRecurringMarginsAndWatermarksUsingPdfPageBoundaries() {
        var raw = new StringBuilder("# 维护手册\r\n\r\n");
        for (int page = 1; page <= 4; page++) {
            if (page > 1) raw.append("\f");
            raw.append("""
                    设备维护工作手册

                    ## 步骤 %d
                    第 %d 项：核对安全装置并记录当天的现场数据。

                    内部资料

                    数据采用实测值，设备状态和巡检结果均需保留。
                    版本：2026-01

                    维修部归档
                    %d
                    """.formatted(page, page, page));
        }
        var cleaned = DocumentCleaner.clean(raw.toString());
        assertThat(cleaned.markdown()).startsWith("# 维护手册")
                .doesNotContain("设备维护工作手册", "维修部归档", "内部资料", "\f")
                .contains("版本：2026-01", "第 4 项：核对安全装置");
        assertThat(cleaned.markdown().lines()).doesNotContain("1", "2", "3", "4");
        assertThat(cleaned.pageSections()).isEqualTo(4);
        assertThat(cleaned.removedMarginLines()).isEqualTo(8);
        assertThat(cleaned.removedPageNumbers()).isEqualTo(4);
        assertThat(cleaned.removedWatermarkLines()).isEqualTo(4);
        assertThat(cleaned.removedDuplicateBlocks()).isZero();
    }

    @Test
    void deduplicatesAdjacentParagraphsAndPreservesMarkdownAndNumericalDifferences() {
        String paragraph = "设备停止后必须等待压力完全释放，并记录检修人员和实际检测时间。";
        String fence = "\u0060\u0060\u0060";
        String raw = "\ufeff# 维修操作\r\n\r\n水印：草稿\r\n\r\n" + paragraph + "\r\n\r\n" + paragraph + "\r\n\r\n" + """
                压力 10 MPa 适用于第一阶段，具体操作需记录在设备维护管理系统。

                压力 11 MPa 适用于第一阶段，具体操作需记录在设备维护管理系统。

                | 指标 | 值 |
                | --- | --- |
                | 压力 | 11 MPa |

                <table>
                <tr><td>水印：表内数据</td></tr>

                <tr><td>禁忌与安全注意事项</td></tr>
                </table>

                $$
                P = F / A
                $$

                %s
                watermark: this is example code

                return true;
                %s

                ## 附录

                %s

                仅供参考，使用前请核对设备型号。
                """.formatted(fence, fence, paragraph);
        var cleaned = DocumentCleaner.clean(raw);
        assertThat(cleaned.markdown()).doesNotContain("\ufeff", "\r", "水印：草稿")
                .contains("压力 10 MPa", "压力 11 MPa", "| 压力 | 11 MPa |", "仅供参考，使用前请核对设备型号。")
                .contains("<table>\n<tr><td>水印：表内数据</td></tr>\n\n<tr><td>禁忌与安全注意事项</td></tr>\n</table>")
                .contains("$$\nP = F / A\n$$")
                .contains(fence + "\nwatermark: this is example code\n\nreturn true;\n" + fence);
        assertThat(cleaned.markdown().lines().filter(paragraph::equals)).hasSize(2);
        assertThat(cleaned.removedWatermarkLines()).isEqualTo(1);
        assertThat(cleaned.removedDuplicateBlocks()).isEqualTo(1);
        assertThat(cleaned.removedMarginLines()).isZero();
        assertThat(DocumentCleaner.clean(cleaned.markdown()).markdown()).isEqualTo(cleaned.markdown());
    }

    @Test
    void preservesPossibleBodyContentWhenThereIsNoPositionEvidence() {
        String raw = """
                # 文档

                第 1 页

                机密

                200

                来源：安全操作指南

                当前用量和有效日期均需逐项核对。
                """.strip();
        var cleaned = DocumentCleaner.clean(raw);
        assertThat(cleaned.markdown()).isEqualTo(raw);
        assertThat(cleaned.removedWatermarkLines()).isZero();
        assertThat(cleaned.removedPageNumbers()).isZero();
    }

    @Test
    void preservesTextThatAppearsInsideTheBodyAsWellAsAtPageEdges() {
        String raw = String.join("\f",
                "必须按期核验\n独立说明一\n正文段一\n内容一\n附件一",
                "必须按期核验\n独立说明二\n正文段二\n内容二\n附件二",
                "必须按期核验\n独立说明三\n必须按期核验\n内容三\n附件三");
        var cleaned = DocumentCleaner.clean(raw);
        assertThat(cleaned.markdown().lines().filter("必须按期核验"::equals)).hasSize(4);
        assertThat(cleaned.removedMarginLines()).isZero();
    }
}
