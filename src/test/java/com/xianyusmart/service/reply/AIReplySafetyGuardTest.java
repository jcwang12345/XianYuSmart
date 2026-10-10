package com.xianyusmart.service.reply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AIReplySafetyGuardTest {

    private final AIReplySafetyGuard guard = new AIReplySafetyGuard();
    private static final String LINK_FREE_FALLBACK = "使用说明请查看订单发货信息，如需帮助请通过闲鱼联系卖家。";

    @Test
    void rejectsInternalServiceErrors() {
        assertNull(guard.safeOrNull("【AI服务错误】模型请求超时"));
        assertNull(guard.safeOrNull("AI服务暂未配置，请配置API Key"));
    }

    @Test
    void replacesOffPlatformGuidance() {
        assertEquals("为保障双方权益，请直接通过闲鱼平台沟通和交易。",
                guard.safeOrNull("请加微信联系我交易"));
    }

    @Test
    void removesAssistantPrefix() {
        assertEquals("您好，商品还在。", guard.safeOrNull("助手：您好，商品还在。"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请打开https://localhost/help", "HTTP://example.com", "ftp://files.example/help",
            "wss://localhost/socket", "mailto:support@example.com", "tel:12345678",
            "请访问www.example.com", "说明在example.com/path", "help.example.cn:8080/guide",
            "打开例子.cn", "例子.中国/help", "пример.рф", "xn--fsqu00a.xn--fiqs8s", "//localhost/help",
            "客服：地址127.0.0.1:8080/help", "192.168.1.1", "255.255.255.255",
            "[2001:db8::1]:8080", "::1", "fe80::", "2001:db8:0:0:0:0:0:1",
            "[售后](help)", "![二维码](qr.png)", "[售后][support]", "[support]: /help",
            "[售后](\nhelp\n)", "[安装\n说明](guide.docx)",
            "<a href='help'>售后</a>", "<area href='help'>", "<img src='qr.png'>", "<svg><rect/></svg>",
            "二维码：data:image/png;base64,AAA", "iVBORw0KGgoAAAANSUhEUgAAAAE",
            "/9j/AAAAAAAAAAAAAAAA", "R0lGODlhAAAAAAAAAAAA", "UklGRgAAAAAAAAAAAAAA",
            "Qk06AAAAAAAAADYAAAAoAAAAAQAAAAEAAAABABgAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAA////AA==",
            "PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjwvc3ZnPg=="
    })
    void replacesLinksAndImagePayloadWithOrderInformationGuidance(String reply) {
        assertEquals(LINK_FREE_FALLBACK, guard.safeOrNull(reply));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请打开订单收到的发货/提货入口，按页面提示扫码进入售后群，群内有客服协助。",
            "请扫描二维码进入售后群。", "使用说明请查看订单发货信息。",
            "请检查方案.docx和表格.xlsx。", "文件为PPT.pptx、说明.pdf、数据.csv。",
            "请查看方案.docm、报价.xlsm和演示.pptm。",
            "请打开WPS文档.wps和演示.dps。",
            "版本 v1.2.3，约10:30:45完成。", "请依次完成[步骤一]和[步骤二]。",
            "请通过闲鱼联系卖家。"
    })
    void preservesPlainGuidanceDocumentNamesAndVersionNumbers(String reply) {
        assertEquals(reply, guard.safeOrNull(reply));
    }

    @Test
    void preservesLegacyErrorAndOffPlatformPriorityWhenLinksArePresent() {
        assertNull(guard.safeOrNull("【AI服务错误】请加微信https://example.com"));
        assertEquals("为保障双方权益，请直接通过闲鱼平台沟通和交易。",
                guard.safeOrNull("请加微信联系我https://example.com"));
    }

    @Test
    void checksTheCompleteReplyBeforeTruncatingIt() {
        assertEquals(LINK_FREE_FALLBACK, guard.safeOrNull("好".repeat(620) + "https://example.com"));
    }
}
