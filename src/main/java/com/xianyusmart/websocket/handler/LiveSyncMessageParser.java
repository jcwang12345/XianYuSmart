package com.xianyusmart.websocket.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuChatMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parses the decrypted records carried by /s/para and /s/sync.
 *
 * <p>The platform currently emits both the legacy numeric-field shape and a
 * card-update shape where field {@code 1} is a string and extension metadata
 * lives in field {@code 4}. Keep this parser pure so protocol fixtures can be
 * tested without opening a real account connection.</p>
 */
@Component
public class LiveSyncMessageParser {

    private final ObjectMapper objectMapper;

    public LiveSyncMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParseResult parse(Long accountId, String lwp, String decryptedData) {
        if (accountId == null || decryptedData == null || decryptedData.isBlank()) {
            return ParseResult.ignored("EMPTY_PAYLOAD");
        }
        try {
            Map<String, Object> root = objectMapper.readValue(decryptedData, new TypeReference<>() { });
            if ("2".equals(text(root.get("2")))) {
                return ParseResult.ignored("READ_RECEIPT");
            }

            Map<String, Object> inner = map(root.get("1"));
            Map<String, Object> extension = map(inner.get("10"));
            boolean cardUpdate = inner.isEmpty() && root.get("1") instanceof String;
            if (cardUpdate) {
                extension = map(root.get("4"));
            }

            Map<String, Object> card = nestedMap(inner, "6", "3");
            if (extension.isEmpty() && card.isEmpty()) {
                return ParseResult.ignored("UNSUPPORTED_RECORD_SHAPE");
            }

            String sid = cardUpdate ? text(root.get("2")) : text(inner.get("2"));
            if (sid.isBlank()) {
                return ParseResult.ignored("MISSING_CONVERSATION_ID");
            }

            Map<String, Object> content = decodeContent(card);
            String reminderContent = firstText(extension, "reminderContent", "summary");
            String messageContent = messageContent(reminderContent, content, card);
            Integer contentType = integer(content.get("contentType"));
            if (contentType == null) {
                contentType = inferContentType(messageContent, extension.isEmpty());
            }

            String senderUserId = firstText(extension, "senderUserId", "senderId", "fromId");
            String senderUserName = firstText(extension, "senderNick", "senderUserName", "reminderTitle");
            if (senderUserId.isBlank() && contentType != null && contentType != 1) {
                senderUserId = "system";
            }
            if (senderUserName.isBlank() && contentType != null && contentType != 1) {
                senderUserName = "系统";
            }

            Long messageTime = firstLong(
                    cardUpdate ? root.get("5") : inner.get("5"),
                    root.get("5"), extension.get("messageTime"));
            String reminderUrl = firstText(extension, "reminderUrl", "url");
            String itemId = firstNonBlank(
                    queryParam(reminderUrl, "itemId"),
                    jsonField(extension.get("bizTag"), "itemId"),
                    jsonField(extension.get("extJson"), "itemId"),
                    findStringValue(content, List.of("itemId", "itemid")),
                    queryParam(findStringValue(content, List.of("jumpUrl", "targetUrl")), "itemId"));

            String pnmId = firstNonBlank(
                    text(inner.get("3")),
                    jsonField(extension.get("bizTag"), "messageId"),
                    jsonField(extension.get("extJson"), "messageId"),
                    text(extension.get("messageId")));
            if (pnmId.isBlank()) {
                String stableSource = accountId + "|" + sid + "|" + nullSafe(messageTime)
                        + "|" + senderUserId + "|" + decryptedData;
                pnmId = "fallback-" + UUID.nameUUIDFromBytes(
                        stableSource.getBytes(StandardCharsets.UTF_8));
            }

            XianyuChatMessage message = new XianyuChatMessage();
            message.setXianyuAccountId(accountId);
            message.setLwp(lwp);
            message.setPnmId(pnmId);
            message.setSId(sid);
            message.setContentType(contentType);
            message.setMsgContent(messageContent);
            message.setSenderUserName(blankToNull(senderUserName));
            message.setSenderUserId(blankToNull(senderUserId));
            message.setSenderAppV(blankToNull(firstText(extension, "_appVersion")));
            message.setSenderOsType(blankToNull(firstText(extension, "_platform")));
            message.setReminderUrl(blankToNull(reminderUrl));
            message.setXyGoodsId(blankToNull(itemId));
            message.setCompleteMsg(decryptedData);
            message.setMessageTime(messageTime);
            return ParseResult.parsed(message);
        } catch (Exception ignored) {
            return ParseResult.ignored("INVALID_DECRYPTED_JSON");
        }
    }

    private Map<String, Object> decodeContent(Map<String, Object> card) {
        for (String key : List.of("5", "1")) {
            String raw = text(card.get(key));
            if (raw.isBlank()) continue;
            Map<String, Object> decoded = parseJsonObject(raw);
            if (!decoded.isEmpty()) return decoded;
            try {
                decoded = parseJsonObject(new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8));
                if (!decoded.isEmpty()) return decoded;
            } catch (IllegalArgumentException ignored) {
                // Not Base64; the next candidate may still be usable.
            }
        }
        return Map.of();
    }

    private String messageContent(String reminderContent, Map<String, Object> content,
                                  Map<String, Object> card) {
        Integer contentType = integer(content.get("contentType"));
        if (Integer.valueOf(1).equals(contentType)) {
            String value = firstText(map(content.get("text")), "text", "content");
            if (!value.isBlank()) return value;
        }
        if (Integer.valueOf(2).equals(contentType)) {
            Object pics = map(content.get("image")).get("pics");
            if (pics instanceof List<?> list) {
                StringBuilder urls = new StringBuilder();
                for (Object item : list) {
                    String url = firstText(map(item), "url");
                    if (url.isBlank()) continue;
                    if (!urls.isEmpty()) urls.append('\n');
                    urls.append(url.startsWith("//") ? "https:" + url : url);
                }
                if (!urls.isEmpty()) return urls.toString();
            }
        }
        if (!reminderContent.isBlank()) return reminderContent;
        String summary = firstText(card, "2", "summary");
        if (!summary.isBlank()) return summary;
        return firstNonBlank(
                findStringValue(content, List.of("title", "content", "text")),
                "[系统消息]");
    }

    private Integer inferContentType(String content, boolean systemShape) {
        if (content != null && (content.contains("已付款，待发货")
                || content.contains("我已付款，等待你发货")
                || content.contains("退款申请"))) {
            return 26;
        }
        return systemShape ? 32 : 1;
    }

    private Map<String, Object> nestedMap(Map<String, Object> source, String... keys) {
        Map<String, Object> current = source;
        for (String key : keys) {
            current = map(current.get(key));
            if (current.isEmpty()) return Map.of();
        }
        return current;
    }

    private Map<String, Object> parseJsonObject(String value) {
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            return map(parsed);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String jsonField(Object jsonValue, String field) {
        String raw = text(jsonValue);
        if (raw.isBlank()) return "";
        return text(parseJsonObject(raw).get(field));
    }

    private String findStringValue(Object value, List<String> keys) {
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (keys.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(key))) {
                    String found = text(entry.getValue());
                    if (!found.isBlank()) return found;
                }
            }
            for (Object child : source.values()) {
                String found = findStringValue(child, keys);
                if (!found.isBlank()) return found;
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                String found = findStringValue(child, keys);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }

    private String queryParam(String value, String key) {
        if (value == null || value.isBlank()) return "";
        int start = value.indexOf(key + "=");
        if (start < 0) return "";
        start += key.length() + 1;
        int end = value.indexOf('&', start);
        return end < 0 ? value.substring(start) : value.substring(start, end);
    }

    private Long firstLong(Object... values) {
        for (Object value : values) {
            try {
                if (value == null) continue;
                long number = Long.parseLong(String.valueOf(value));
                return number > 0 && number < 100_000_000_000L ? number * 1000 : number;
            } catch (NumberFormatException ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private Integer integer(Object value) {
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ParseResult(XianyuChatMessage message, String ignoredReason) {
        static ParseResult parsed(XianyuChatMessage message) {
            return new ParseResult(message, null);
        }

        static ParseResult ignored(String reason) {
            return new ParseResult(null, reason);
        }

        public boolean parsed() {
            return message != null;
        }
    }
}
