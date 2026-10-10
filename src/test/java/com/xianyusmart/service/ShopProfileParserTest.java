package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ShopProfileParserTest {

    private final ShopProfileParser parser = new ShopProfileParser(new ObjectMapper());

    @Test
    void parsesVerifiedZeroValuesAndDynamicLabelsWithoutInventingMissingFields() {
        String head = """
                {"ret":["SUCCESS::调用成功"],"data":{"baseInfo":{"kcUserId":"39175043","userType":1,
                "tags":{"idle_zhima_zheng":true,"real_name_certification_77":true,
                "real_person_certification_77":true,"tb_xianyu_user":true,"xianyu_user_upgrade":true,
                "alibaba_idle_playboy":false}},"module":{"base":{"displayName":"抖抖要自由",
                "avatar":{"avatar":"http://img.alicdn.com/a.png"},"ipLocation":"河北省",
                "ylzTags":[{"attributes":{"role":"seller"},"text":"卖家信用极好"},
                {"attributes":{"role":"buyer"},"text":"买家信用极好"}]},
                "shop":{"level":"L3","score":0,"superShow":true,"itemToppingLimit":1,
                "reviewNum":52,"praiseRatio":100},"social":{"followers":0},
                "tabs":{"item":{"number":12}}}}}
                """;
        String nav = """
                {"ret":["SUCCESS::调用成功"],"data":{"module":{"base":{"purchaseCount":66,"soldCount":142}}}}
                """;
        String items = """
                {"ret":["SUCCESS::调用成功"],"data":{"itemGroupList":[{"groupName":"综合"},{"groupName":"在售"},{"groupName":"已售出"}]}}
                """;

        ShopProfileParser.ParsedProfile profile = parser.parse("39175043", head, nav, items);

        assertEquals("抖抖要自由", profile.shopNickname());
        assertEquals("https://img.alicdn.com/a.png", profile.avatarUrl());
        assertEquals(BigDecimal.ZERO, profile.shopScore());
        assertEquals(0L, profile.followerCount());
        assertEquals(new BigDecimal("1.0000"), profile.positiveRate());
        assertEquals("卖家信用极好", profile.sellerCredit());
        assertTrue(profile.realNameVerified());
        assertFalse(profile.xianyuExpert());
        assertEquals(12, profile.onSaleCount());
        assertEquals("VERIFIED", profile.fieldEvidence().get("onSaleCount").status());
        assertEquals("UNSUPPORTED", profile.fieldEvidence().get("platformOnlineStatus").status());
        assertEquals("PARTIAL", profile.coverageStatus());
    }

    @Test
    void rejectsCrossAccountResponse() {
        String head = """
                {"ret":["SUCCESS::调用成功"],"data":{"baseInfo":{"kcUserId":"other"},"module":{}}}
                """;
        String ok = "{\"ret\":[\"SUCCESS\"],\"data\":{}}";

        ShopProfileParser.ProfileParseException error = assertThrows(
                ShopProfileParser.ProfileParseException.class,
                () -> parser.parse("expected", head, ok, ok));

        assertEquals("IDENTITY_MISMATCH", error.code());
    }

    @Test
    void rejectsNonSuccessPayload() {
        String denied = "{\"ret\":[\"FAIL_SYS_TOKEN_EXOIRED::令牌过期\"],\"data\":{}}";
        ShopProfileParser.ProfileParseException error = assertThrows(
                ShopProfileParser.ProfileParseException.class,
                () -> parser.parse("1", denied, denied, denied));
        assertEquals("PLATFORM_REJECTED", error.code());
    }

    @Test
    void acceptsCanonicalPlatformIntegerStringsButRejectsFractionalCounts() {
        String head = """
                {"ret":["SUCCESS"],"data":{"baseInfo":{"kcUserId":"39175043","tags":{}},
                "module":{"base":{"displayName":"店铺"},"shop":{},
                "social":{"followers":"28.0"},"tabs":{"item":{"number":"12"}}}}}
                """;
        String nav = """
                {"ret":["SUCCESS"],"data":{"module":{"base":{"purchaseCount":"66","soldCount":"142.5"}}}}
                """;
        String items = "{\"ret\":[\"SUCCESS\"],\"data\":{}}";

        ShopProfileParser.ParsedProfile profile = parser.parse("39175043", head, nav, items);

        assertEquals(28L, profile.followerCount());
        assertEquals(66L, profile.totalBoughtCount());
        assertNull(profile.totalSoldCount());
        assertEquals("INVALID", profile.fieldEvidence().get("totalSoldCount").status());
    }

    @Test
    void distinguishesMissingMalformedFalseAndOverflowWithoutInventingValues() {
        String ok = "{\"ret\":[\"SUCCESS\"],\"data\":{}}";
        ShopProfileParser.ParsedProfile missing = parser.parse("u", head("", "\"superShow\":\"false\",\"itemToppingLimit\":2147483648"), ok, ok);
        assertNull(missing.sesameVerified());
        assertEquals("NOT_RETURNED", missing.fieldEvidence().get("sesameVerified").status());
        assertNull(missing.superSeller());
        assertEquals("INVALID", missing.fieldEvidence().get("superSeller").status());
        assertNull(missing.pinLimit());
        assertEquals("INVALID", missing.fieldEvidence().get("pinLimit").status());

        ShopProfileParser.ParsedProfile explicitFalse = parser.parse("u",
                head(",\"tags\":{\"idle_zhima_zheng\":false}", "\"superShow\":false,\"itemToppingLimit\":0"), ok, ok);
        assertEquals(Boolean.FALSE, explicitFalse.sesameVerified());
        assertEquals("VERIFIED", explicitFalse.fieldEvidence().get("sesameVerified").status());
        assertEquals(Boolean.FALSE, explicitFalse.superSeller());
        assertEquals(0, explicitFalse.pinLimit());

        ShopProfileParser.ParsedProfile malformed = parser.parse("u",
                head(",\"tags\":[]", "\"superShow\":null,\"itemToppingLimit\":null"), ok, ok);
        assertNull(malformed.sesameVerified());
        assertEquals("INVALID", malformed.fieldEvidence().get("sesameVerified").status());
        assertEquals("NOT_RETURNED", malformed.fieldEvidence().get("superSeller").status());
    }

    @Test
    void rejectsNegativeCountsAndOutOfRangePercentagesAsInvalid() {
        String head = """
                {"ret":["SUCCESS"],"data":{"baseInfo":{"kcUserId":"u","tags":{}},"module":{
                "base":{},"shop":{"reviewNum":-1,"praiseRatio":101},"social":{"followers":-2},"tabs":{}}}}
                """;
        String nav = "{\"ret\":[\"SUCCESS\"],\"data\":{\"module\":{\"base\":{\"purchaseCount\":-3,\"soldCount\":-4}}}}";
        String items = "{\"ret\":[\"SUCCESS\"],\"data\":{}}";

        ShopProfileParser.ParsedProfile profile = parser.parse("u", head, nav, items);

        for (String field : java.util.List.of("totalReviewCount", "followerCount", "positiveRate",
                "totalBoughtCount", "totalSoldCount")) {
            assertNull(switch (field) {
                case "totalReviewCount" -> profile.totalReviewCount();
                case "followerCount" -> profile.followerCount();
                case "positiveRate" -> profile.positiveRate();
                case "totalBoughtCount" -> profile.totalBoughtCount();
                default -> profile.totalSoldCount();
            });
            assertEquals("INVALID", profile.fieldEvidence().get(field).status(), field);
        }
    }

    private String head(String tagsFragment, String shopFields) {
        return "{\"ret\":[\"SUCCESS\"],\"data\":{\"baseInfo\":{\"kcUserId\":\"u\"" + tagsFragment
                + "},\"module\":{\"base\":{},\"shop\":{" + shopFields + "},\"social\":{},\"tabs\":{}}}}";
    }
}
