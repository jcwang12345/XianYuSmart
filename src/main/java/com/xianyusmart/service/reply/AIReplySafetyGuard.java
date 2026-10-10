package com.xianyusmart.service.reply;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** AI咨询输出门禁，避免把内部错误、平台外引导、链接或图片载荷发给买家。 */
@Component
public class AIReplySafetyGuard {

    private static final int MAX_REPLY_CHARS = 600;
    private static final List<String> INTERNAL_ERROR_MARKERS = List.of(
            "ai服务暂未配置", "ai回复生成失败", "【ai服务错误】", "api key未配置");
    private static final List<String> OFF_PLATFORM_MARKERS = List.of(
            "加微信", "微信联系", "加qq", "qq联系", "银行卡转账", "线下交易", "脱离平台");
    private static final String LINK_FREE_FALLBACK = "使用说明请查看订单发货信息，如需帮助请通过闲鱼联系卖家。";
    private static final Pattern PROTOCOL_LINK = Pattern.compile(
            "(?:(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*\\s*://|(?<![a-z0-9])(?:mailto|tel|sms|file|data|javascript)\\s*:|(?<![:\\w])//(?=[a-z0-9\\[]))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN = Pattern.compile(
            "(?<![a-z0-9_.-])(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+"
                    + "([a-z]{2,63}|xn--[a-z0-9-]{2,59}|\\p{L}{2,63})(?![a-z0-9_-])",
            Pattern.CASE_INSENSITIVE);
    // Common document names are not network addresses; an explicit URL is still blocked above.
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "doc", "docx", "docm", "dotx", "dotm", "xls", "xlsx", "xlsm", "xltx", "xltm",
            "ppt", "pptx", "pptm", "potx", "potm", "wps", "dps", "pdf", "txt", "csv", "tsv");
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})";
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![a-z0-9_.])" + IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}(?![a-z0-9_.])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV6_CANDIDATE = Pattern.compile(
            "(?<![a-z0-9:])[a-f0-9:]*:[a-f0-9:]+(?:%[a-z0-9_.-]+)?(?![a-z0-9:])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "!?\\[[^\\]]*]\\s*(?:\\([^)]*\\)|\\[[^\\]]*])|^\\s*\\[[^\\]]+]:\\s*\\S+",
            Pattern.MULTILINE);
    private static final Pattern HTML_LINK_OR_IMAGE = Pattern.compile(
            "<\\s*(?:(?:a|area)\\b[^>]*\\bhref|(?:img|image|iframe|source|video|audio|object|embed|link)\\b[^>]*\\b(?:src|href|data)|svg\\b)[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMAGE_PAYLOAD = Pattern.compile(
            "(?:data\\s*:\\s*image/|iVBORw0KGgo[A-Za-z0-9+/=]{12,}|/9j/[A-Za-z0-9+/=]{12,}|R0lGOD[A-Za-z0-9+/=]{12,}|UklGR[A-Za-z0-9+/=]{12,}|Qk[A-Za-z0-9+/=]{40,}|PHN2Zy[A-Za-z0-9+/=]{12,})",
            Pattern.CASE_INSENSITIVE);

    public String safeOrNull(String reply) {
        if (reply == null || reply.isBlank()) return null;
        String normalized = reply.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (INTERNAL_ERROR_MARKERS.stream().anyMatch(lower::contains)) return null;
        if (OFF_PLATFORM_MARKERS.stream().anyMatch(lower::contains)) {
            return "为保障双方权益，请直接通过闲鱼平台沟通和交易。";
        }
        if (containsLinkOrImagePayload(normalized)) return LINK_FREE_FALLBACK;
        normalized = normalized.replaceFirst("^(assistant|助手|客服)\\s*[:：]\\s*", "").trim();
        if (normalized.length() > MAX_REPLY_CHARS) {
            normalized = normalized.substring(0, MAX_REPLY_CHARS).trim() + "…";
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean containsLinkOrImagePayload(String reply) {
        if (PROTOCOL_LINK.matcher(reply).find() || IPV4.matcher(reply).find()
                || MARKDOWN_LINK.matcher(reply).find() || HTML_LINK_OR_IMAGE.matcher(reply).find()
                || IMAGE_PAYLOAD.matcher(reply).find()) return true;
        var domains = DOMAIN.matcher(reply);
        while (domains.find()) {
            if (!DOCUMENT_EXTENSIONS.contains(domains.group(1).toLowerCase(Locale.ROOT))) return true;
        }
        var addresses = IPV6_CANDIDATE.matcher(reply);
        while (addresses.find()) {
            String address = addresses.group().split("%", 2)[0];
            if (address.indexOf(':') == address.lastIndexOf(':')) continue;
            boolean compressed = address.contains("::");
            if (address.contains(":::") || (compressed && address.indexOf("::") != address.lastIndexOf("::"))) continue;
            String[] groups = address.split(":", -1);
            int nonEmptyGroups = 0;
            boolean valid = true;
            for (String group : groups) {
                if (group.isEmpty()) continue;
                if (group.length() > 4) { valid = false; break; }
                nonEmptyGroups++;
            }
            if (valid && (compressed ? nonEmptyGroups < 8 : nonEmptyGroups == 8)) return true;
        }
        return false;
    }
}
