package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** 将已核验的闲鱼个人页只读接口响应转换为可追溯的店铺画像。 */
@Component
public class ShopProfileParser {

    public static final String COLLECTOR_VERSION = "B03_V62_1";
    private final ObjectMapper objectMapper;

    public ShopProfileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedProfile parse(String expectedUserId, String headJson, String navJson, String itemJson) {
        try {
            JsonNode head = successData(headJson, "店铺头部接口");
            JsonNode nav = successData(navJson, "店铺导航接口");
            JsonNode item = successData(itemJson, "店铺商品接口");
            String actualUserId = text(head, "/baseInfo/kcUserId");
            if (actualUserId == null || !actualUserId.equals(expectedUserId)) {
                throw new ProfileParseException("IDENTITY_MISMATCH", "平台返回账号身份与目标账号不一致");
            }

            JsonNode base = head.path("module").path("base");
            JsonNode shop = head.path("module").path("shop");
            JsonNode social = head.path("module").path("social");
            JsonNode tabs = head.path("module").path("tabs");
            JsonNode navBase = nav.path("module").path("base");
            Map<String, FieldEvidence> evidence = new LinkedHashMap<>();

            String nickname = stringField(evidence, "shopNickname", base.path("displayName"),
                    "PLATFORM_API", "$.data.module.base.displayName");
            String avatar = avatarField(evidence, base.path("avatar").path("avatar"));
            String region = stringField(evidence, "region", base.path("ipLocation"),
                    "PLATFORM_API", "$.data.module.base.ipLocation");
            String level = stringField(evidence, "shopLevel", shop.path("level"),
                    "PLATFORM_API", "$.data.module.shop.level");
            BigDecimal score = decimalField(evidence, "shopScore", shop.path("score"),
                    "$.data.module.shop.score");
            Boolean superSeller = booleanField(evidence, "superSeller", shop.path("superShow"),
                    "$.data.module.shop.superShow");
            Integer pinLimit = integerField(evidence, "pinLimit", shop.path("itemToppingLimit"),
                    "$.data.module.shop.itemToppingLimit");
            Long followers = longField(evidence, "followerCount", social.path("followers"),
                    "$.data.module.social.followers");
            Long reviews = longField(evidence, "totalReviewCount", shop.path("reviewNum"),
                    "$.data.module.shop.reviewNum");
            BigDecimal positiveRate = percentField(evidence, shop.path("praiseRatio"));
            Long totalBought = longField(evidence, "totalBoughtCount", navBase.path("purchaseCount"),
                    "$.data.module.base.purchaseCount");
            Long totalSold = longField(evidence, "totalSoldCount", navBase.path("soldCount"),
                    "$.data.module.base.soldCount");
            Integer onSaleCount = integerField(evidence, "onSaleCount", tabs.path("item").path("number"),
                    "$.data.module.tabs.item.number");

            Map<String, String> credit = creditLabels(base.path("ylzTags"));
            String sellerCredit = creditField(evidence, "sellerCredit", credit.get("seller"));
            String buyerCredit = creditField(evidence, "buyerCredit", credit.get("buyer"));
            JsonNode tagNode = head.path("baseInfo").path("tags");
            List<String> tags = tags(tagNode);
            Boolean sesame = tagField(evidence, "sesameVerified", tagNode, "idle_zhima_zheng");
            Boolean realName = tagField(evidence, "realNameVerified", tagNode, "real_name_certification_77");
            Boolean expert = tagField(evidence, "xianyuExpert", tagNode, "alibaba_idle_playboy");
            Boolean upgraded = tagField(evidence, "xianyuUpgraded", tagNode, "xianyu_user_upgrade");
            Boolean taobaoBound = tagField(evidence, "taobaoBound", tagNode, "tb_xianyu_user");
            String userType = stringField(evidence, "userType", head.path("baseInfo").path("userType"),
                    "PLATFORM_API", "$.data.baseInfo.userType");

            unsupported(evidence, "platformOnlineStatus", "平台接口未返回在线状态");
            unsupported(evidence, "lastActiveTime", "平台接口未返回最后活跃时间");
            notReturned(evidence, "soldCount", "商品分组首包未返回可信数量");
            unsupported(evidence, "shopAuthorization", "个人页接口不提供店铺授权状态");

            Map<String, Object> identity = Map.of(
                    "expectedUserId", expectedUserId,
                    "platformUserId", actualUserId,
                    "verified", true,
                    "evidencePath", "$.data.baseInfo.kcUserId");
            List<String> certificationLabels = new ArrayList<>();
            if (Boolean.TRUE.equals(sesame)) certificationLabels.add("芝麻信用认证");
            if (Boolean.TRUE.equals(realName)) certificationLabels.add("实名认证");
            if (tags.contains("real_person_certification_77")) certificationLabels.add("真人认证");
            certificationLabels.addAll(credit.values());

            Map<String, Object> safeRaw = new LinkedHashMap<>();
            safeRaw.put("identity", identity);
            safeRaw.put("fieldEvidence", evidence);
            safeRaw.put("certificationLabels", certificationLabels);
            safeRaw.put("observedItemGroupNames", groupNames(item.path("itemGroupList")));

            return new ParsedProfile(nickname, avatar,
                    "https://www.goofish.com/personal?userId=" + expectedUserId,
                    region, level, score, superSeller, sellerCredit, buyerCredit, sesame, realName,
                    expert, userType, upgraded, taobaoBound, pinLimit, onSaleCount, null, followers,
                    positiveRate, reviews, totalBought, totalSold, evidence, certificationLabels,
                    identity, safeRaw, completeness(evidence));
        } catch (ProfileParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ProfileParseException("INVALID_RESPONSE", "店铺画像响应无法解析");
        }
    }

    /** API 字段优先；页面证据只补 API 未核验的字段，绝不覆盖 API 已核验值。 */
    public ParsedProfile parseMerged(String expectedUserId, ShopProfileCollector.CollectionResult collected) {
        ParsedProfile api = null;
        if (collected.headJson() != null && responseIdentity(collected.headJson()) != null) {
            String actual = responseIdentity(collected.headJson());
            if (!expectedUserId.equals(actual)) {
                throw new ProfileParseException("IDENTITY_MISMATCH", "平台 API 返回账号身份与目标账号不一致");
            }
            api = parse(expectedUserId, collected.headJson(), successOrEmpty(collected.navJson()),
                    successOrEmpty(collected.itemJson()));
        }
        ParsedProfile web = null;
        if (collected.pageHeadJson() != null) {
            web = withSource(parse(expectedUserId, collected.pageHeadJson(),
                    successOrEmpty(collected.pageNavJson()), successOrEmpty(collected.pageItemJson())), "PLATFORM_WEB");
        } else if (api == null && "PLATFORM_WEB".equals(collected.source())) {
            web = withSource(parse(expectedUserId, collected.headJson(), collected.navJson(), collected.itemJson()),
                    "PLATFORM_WEB");
        }
        if (api == null && web == null) {
            throw new ProfileParseException("MISSING_RESPONSE", "没有可核验的店铺身份响应");
        }
        if (api == null) return web;
        if (web == null) return api;
        return merge(api, web, expectedUserId);
    }

    private ParsedProfile merge(ParsedProfile api, ParsedProfile web, String expectedUserId) {
        Map<String, FieldEvidence> evidence = new LinkedHashMap<>();
        for (String field : union(api.fieldEvidence(), web.fieldEvidence())) {
            FieldEvidence a = api.fieldEvidence().get(field), w = web.fieldEvidence().get(field);
            if (a != null && "VERIFIED".equals(a.status())) evidence.put(field, a);
            else if (w != null && "VERIFIED".equals(w.status())) evidence.put(field, w);
            else if (a != null && !("NOT_RETURNED".equals(a.status()) && w != null)) evidence.put(field, a);
            else if (w != null) evidence.put(field, w);
        }
        String nickname = pick(api.shopNickname(), web.shopNickname(), evidence, "shopNickname", api);
        String avatar = pick(api.avatarUrl(), web.avatarUrl(), evidence, "avatarUrl", api);
        String region = pick(api.region(), web.region(), evidence, "region", api);
        String level = pick(api.shopLevel(), web.shopLevel(), evidence, "shopLevel", api);
        BigDecimal score = pick(api.shopScore(), web.shopScore(), evidence, "shopScore", api);
        Boolean superSeller = pick(api.superSeller(), web.superSeller(), evidence, "superSeller", api);
        String sellerCredit = pick(api.sellerCredit(), web.sellerCredit(), evidence, "sellerCredit", api);
        String buyerCredit = pick(api.buyerCredit(), web.buyerCredit(), evidence, "buyerCredit", api);
        Boolean sesame = pick(api.sesameVerified(), web.sesameVerified(), evidence, "sesameVerified", api);
        Boolean realName = pick(api.realNameVerified(), web.realNameVerified(), evidence, "realNameVerified", api);
        Boolean expert = pick(api.xianyuExpert(), web.xianyuExpert(), evidence, "xianyuExpert", api);
        String userType = pick(api.userType(), web.userType(), evidence, "userType", api);
        Boolean upgraded = pick(api.xianyuUpgraded(), web.xianyuUpgraded(), evidence, "xianyuUpgraded", api);
        Boolean taobaoBound = pick(api.taobaoBound(), web.taobaoBound(), evidence, "taobaoBound", api);
        Integer pinLimit = pick(api.pinLimit(), web.pinLimit(), evidence, "pinLimit", api);
        Integer onSale = pick(api.onSaleCount(), web.onSaleCount(), evidence, "onSaleCount", api);
        Integer sold = pick(api.soldCount(), web.soldCount(), evidence, "soldCount", api);
        Long followers = pick(api.followerCount(), web.followerCount(), evidence, "followerCount", api);
        BigDecimal positive = pick(api.positiveRate(), web.positiveRate(), evidence, "positiveRate", api);
        Long reviews = pick(api.totalReviewCount(), web.totalReviewCount(), evidence, "totalReviewCount", api);
        Long bought = pick(api.totalBoughtCount(), web.totalBoughtCount(), evidence, "totalBoughtCount", api);
        Long totalSold = pick(api.totalSoldCount(), web.totalSoldCount(), evidence, "totalSoldCount", api);
        List<String> labels = new ArrayList<>();
        if (Boolean.TRUE.equals(sesame)) labels.add("芝麻信用认证");
        if (Boolean.TRUE.equals(realName)) labels.add("实名认证");
        if (sellerCredit != null) labels.add(sellerCredit);
        if (buyerCredit != null) labels.add(buyerCredit);
        Map<String, Object> identity = Map.of("expectedUserId", expectedUserId,
                "platformUserId", expectedUserId, "verified", true,
                "evidencePath", "$.data.baseInfo.kcUserId", "validatedResponses", List.of(HEAD_API_NAME, NAV_API_NAME, ITEM_API_NAME));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("identity", identity);
        raw.put("fieldEvidence", evidence);
        raw.put("certificationLabels", labels);
        raw.put("mergePolicy", "API_VERIFIED_FIRST_PAGE_FILLS_GAPS");
        return new ParsedProfile(nickname, avatar, api.shopHomeUrl(), region, level, score, superSeller,
                sellerCredit, buyerCredit, sesame, realName, expert, userType, upgraded, taobaoBound,
                pinLimit, onSale, sold, followers, positive, reviews, bought, totalSold, evidence, labels,
                identity, raw, completeness(evidence));
    }

    private static final String HEAD_API_NAME = "mtop.idle.web.user.page.head";
    private static final String NAV_API_NAME = "mtop.idle.web.user.page.nav";
    private static final String ITEM_API_NAME = "mtop.idle.web.xyh.item.list";

    private <T> T pick(T apiValue, T webValue, Map<String, FieldEvidence> merged,
                       String field, ParsedProfile api) {
        FieldEvidence selected = merged.get(field);
        FieldEvidence apiEvidence = api.fieldEvidence().get(field);
        return selected != null && selected == apiEvidence ? apiValue : webValue;
    }

    private List<String> union(Map<String, FieldEvidence> left, Map<String, FieldEvidence> right) {
        List<String> keys = new ArrayList<>(left.keySet());
        right.keySet().stream().filter(key -> !left.containsKey(key)).forEach(keys::add);
        return keys;
    }

    private ParsedProfile withSource(ParsedProfile profile, String source) {
        Map<String, FieldEvidence> evidence = new LinkedHashMap<>();
        profile.fieldEvidence().forEach((field, value) -> evidence.put(field,
                new FieldEvidence(value.status(), source, value.path(), value.reason(), value.observedAt())));
        Map<String, Object> raw = new LinkedHashMap<>(profile.safeRawEvidence());
        raw.put("fieldEvidence", evidence);
        return new ParsedProfile(profile.shopNickname(), profile.avatarUrl(), profile.shopHomeUrl(), profile.region(),
                profile.shopLevel(), profile.shopScore(), profile.superSeller(), profile.sellerCredit(),
                profile.buyerCredit(), profile.sesameVerified(), profile.realNameVerified(), profile.xianyuExpert(),
                profile.userType(), profile.xianyuUpgraded(), profile.taobaoBound(), profile.pinLimit(),
                profile.onSaleCount(), profile.soldCount(), profile.followerCount(), profile.positiveRate(),
                profile.totalReviewCount(), profile.totalBoughtCount(), profile.totalSoldCount(), evidence,
                profile.certificationLabels(), profile.platformIdentity(), raw, completeness(evidence));
    }

    private String responseIdentity(String json) {
        try {
            return text(successData(json, "店铺头部接口"), "/baseInfo/kcUserId");
        } catch (ProfileParseException e) {
            if ("PLATFORM_REJECTED".equals(e.code())) return null;
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    private String successOrEmpty(String json) {
        return json == null ? "{\"ret\":[\"SUCCESS\"],\"data\":{}}" : json;
    }

    private JsonNode successData(String json, String label) throws Exception {
        if (json == null || json.isBlank()) {
            throw new ProfileParseException("MISSING_RESPONSE", label + "未返回数据");
        }
        String value = json.trim();
        if (!value.startsWith("{")) {
            int first = value.indexOf('(');
            int last = value.lastIndexOf(')');
            if (first < 0 || last <= first) throw new ProfileParseException("INVALID_RESPONSE", label + "格式无效");
            value = value.substring(first + 1, last);
        }
        JsonNode root = objectMapper.readTree(value);
        JsonNode ret = root.path("ret");
        if (!ret.isArray() || ret.isEmpty() || !ret.get(0).asText("").startsWith("SUCCESS")) {
            throw new ProfileParseException("PLATFORM_REJECTED", label + "调用未成功");
        }
        JsonNode data = root.path("data");
        if (!data.isObject()) throw new ProfileParseException("INVALID_RESPONSE", label + "缺少data");
        return data;
    }

    private String avatarField(Map<String, FieldEvidence> evidence, JsonNode node) {
        String value = scalarText(node);
        String path = "$.data.module.base.avatar.avatar";
        if (value == null) {
            evidence.put("avatarUrl", FieldEvidence.notReturned("PLATFORM_API", path));
            return null;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if ("http".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.endsWith("alicdn.com") || host.endsWith("taobaocdn.com"))) {
                value = "https" + value.substring(4);
            }
            URI normalized = URI.create(value);
            if (!"https".equalsIgnoreCase(normalized.getScheme())) throw new IllegalArgumentException();
            evidence.put("avatarUrl", FieldEvidence.verified("PLATFORM_API", path));
            return value;
        } catch (Exception e) {
            evidence.put("avatarUrl", new FieldEvidence("INVALID", "PLATFORM_API", path, "头像地址不是可信HTTPS地址"));
            return null;
        }
    }

    private String stringField(Map<String, FieldEvidence> evidence, String field, JsonNode node,
                               String source, String path) {
        String value = scalarText(node);
        evidence.put(field, value == null ? FieldEvidence.notReturned(source, path) : FieldEvidence.verified(source, path));
        return value;
    }

    private BigDecimal decimalField(Map<String, FieldEvidence> evidence, String field, JsonNode node, String path) {
        if (!node.isNumber() || node.decimalValue().compareTo(BigDecimal.ZERO) < 0) {
            evidence.put(field, node.isMissingNode() || node.isNull()
                    ? FieldEvidence.notReturned("PLATFORM_API", path)
                    : new FieldEvidence("INVALID", "PLATFORM_API", path, "数值无效"));
            return null;
        }
        evidence.put(field, FieldEvidence.verified("PLATFORM_API", path));
        return node.decimalValue();
    }

    private Integer integerField(Map<String, FieldEvidence> evidence, String field, JsonNode node, String path) {
        Long value = longField(evidence, field, node, path);
        if (value != null && value > Integer.MAX_VALUE) {
            evidence.put(field, new FieldEvidence("INVALID", "PLATFORM_API", path, "整数超出可存储范围"));
            return null;
        }
        return value == null ? null : value.intValue();
    }

    private Long longField(Map<String, FieldEvidence> evidence, String field, JsonNode node, String path) {
        Long value = canonicalNonNegativeLong(node);
        if (value == null) {
            evidence.put(field, node.isMissingNode() || node.isNull()
                    ? FieldEvidence.notReturned("PLATFORM_API", path)
                    : new FieldEvidence("INVALID", "PLATFORM_API", path, "非负整数校验失败"));
            return null;
        }
        evidence.put(field, FieldEvidence.verified("PLATFORM_API", path));
        return value;
    }

    private Long canonicalNonNegativeLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isBoolean()) return null;
        try {
            BigDecimal value;
            if (node.isNumber()) {
                value = node.decimalValue();
            } else if (node.isTextual() && node.textValue().matches("0|[1-9]\\d*(?:\\.0+)?")) {
                value = new BigDecimal(node.textValue());
            } else {
                return null;
            }
            BigDecimal normalized = value.stripTrailingZeros();
            if (normalized.signum() < 0 || normalized.scale() > 0
                    || normalized.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return null;
            return normalized.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private Boolean booleanField(Map<String, FieldEvidence> evidence, String field, JsonNode node, String path) {
        if (!node.isBoolean()) {
            evidence.put(field, node.isMissingNode() || node.isNull()
                    ? FieldEvidence.notReturned("PLATFORM_API", path)
                    : new FieldEvidence("INVALID", "PLATFORM_API", path, "布尔字段类型无效"));
            return null;
        }
        evidence.put(field, FieldEvidence.verified("PLATFORM_API", path));
        return node.booleanValue();
    }

    private BigDecimal percentField(Map<String, FieldEvidence> evidence, JsonNode node) {
        String path = "$.data.module.shop.praiseRatio";
        if (!node.isNumber() || node.decimalValue().compareTo(BigDecimal.ZERO) < 0
                || node.decimalValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            evidence.put("positiveRate", node.isMissingNode() || node.isNull()
                    ? FieldEvidence.notReturned("PLATFORM_API", path)
                    : new FieldEvidence("INVALID", "PLATFORM_API", path, "好评率超出0-100"));
            return null;
        }
        evidence.put("positiveRate", FieldEvidence.verified("PLATFORM_API", path));
        return node.decimalValue().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private String creditField(Map<String, FieldEvidence> evidence, String field, String value) {
        String path = "$.data.module.base.ylzTags[*]";
        evidence.put(field, value == null ? FieldEvidence.notReturned("PLATFORM_API", path)
                : FieldEvidence.verified("PLATFORM_API", path));
        return value;
    }

    private Boolean tagField(Map<String, FieldEvidence> evidence, String field, JsonNode tags, String tag) {
        String path = "$.data.baseInfo.tags." + tag;
        if (tags == null || tags.isMissingNode() || tags.isNull()) {
            evidence.put(field, FieldEvidence.notReturned("PLATFORM_API", path));
            return null;
        }
        if (!tags.isObject()) {
            evidence.put(field, new FieldEvidence("INVALID", "PLATFORM_API", path, "认证标签容器类型无效"));
            return null;
        }
        if (!tags.has(tag)) {
            evidence.put(field, FieldEvidence.notReturned("PLATFORM_API", path));
            return null;
        }
        if (!tags.path(tag).isBoolean()) {
            evidence.put(field, new FieldEvidence("INVALID", "PLATFORM_API", path, "认证标签类型无效"));
            return null;
        }
        evidence.put(field, FieldEvidence.verified("PLATFORM_API", path));
        return tags.path(tag).booleanValue();
    }

    private Map<String, String> creditLabels(JsonNode nodes) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!nodes.isArray()) return result;
        for (JsonNode node : nodes) {
            String role = text(node, "/attributes/role");
            String label = scalarText(node.path("text"));
            if (("seller".equals(role) || "buyer".equals(role)) && label != null) result.put(role, label);
        }
        return result;
    }

    private List<String> tags(JsonNode nodes) {
        List<String> result = new ArrayList<>();
        if (!nodes.isObject()) return result;
        nodes.fields().forEachRemaining(entry -> {
            if (entry.getValue().asBoolean(false)) result.add(entry.getKey());
        });
        return result;
    }

    private List<String> groupNames(JsonNode nodes) {
        List<String> result = new ArrayList<>();
        if (!nodes.isArray()) return result;
        for (JsonNode node : nodes) {
            String value = scalarText(node.path("groupName"));
            if (value != null) result.add(value);
        }
        return result;
    }

    private String text(JsonNode node, String pointer) {
        return scalarText(node.at(pointer));
    }

    private String scalarText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) return null;
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private void unsupported(Map<String, FieldEvidence> evidence, String field, String reason) {
        evidence.put(field, new FieldEvidence("UNSUPPORTED", "PLATFORM_API", null, reason));
    }

    private void notReturned(Map<String, FieldEvidence> evidence, String field, String reason) {
        evidence.put(field, new FieldEvidence("NOT_RETURNED", "PLATFORM_API", null, reason));
    }

    private String completeness(Map<String, FieldEvidence> evidence) {
        long verified = evidence.values().stream().filter(value -> "VERIFIED".equals(value.status())).count();
        return verified == evidence.size() ? "FULL" : verified == 0 ? "UNSYNCED" : "PARTIAL";
    }

    public record FieldEvidence(String status, String source, String path, String reason, String observedAt) {
        public FieldEvidence(String status, String source, String path, String reason) {
            this(status, source, path, reason, Instant.now().toString());
        }

        static FieldEvidence verified(String source, String path) {
            return new FieldEvidence("VERIFIED", source, path, null, Instant.now().toString());
        }

        static FieldEvidence notReturned(String source, String path) {
            return new FieldEvidence("NOT_RETURNED", source, path, "平台响应未返回该字段", Instant.now().toString());
        }
    }

    public record ParsedProfile(
            String shopNickname, String avatarUrl, String shopHomeUrl, String region, String shopLevel,
            BigDecimal shopScore, Boolean superSeller, String sellerCredit, String buyerCredit,
            Boolean sesameVerified, Boolean realNameVerified, Boolean xianyuExpert, String userType,
            Boolean xianyuUpgraded, Boolean taobaoBound, Integer pinLimit, Integer onSaleCount,
            Integer soldCount, Long followerCount, BigDecimal positiveRate, Long totalReviewCount,
            Long totalBoughtCount, Long totalSoldCount, Map<String, FieldEvidence> fieldEvidence,
            List<String> certificationLabels, Map<String, Object> platformIdentity,
            Map<String, Object> safeRawEvidence, String coverageStatus) {}

    public static class ProfileParseException extends RuntimeException {
        private final String code;

        public ProfileParseException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
