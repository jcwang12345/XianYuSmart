package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductEventSafetyTest {
    final ProductEventSafety safety=new ProductEventSafety(new ObjectMapper());
    @Test void nestedUnknownCredentialsAndContactTextAreOmittedBeforePersistence() {
        var raw=Map.<String,Object>of("title","Cookie=SYNTHETIC_SENTINEL", "supportPolicy","联系 qa@example.invalid", "stock",8,
                "TOKEN",List.of(Map.of("password","SYNTHETIC_SENTINEL")),"skuSummary",List.of(Map.of("skuId","safe-1","price",1.20,"secret","SYNTHETIC_SENTINEL")));
        var clean=safety.snapshot(raw,true,false);String text=safety.canonical(clean.content());
        assertFalse(text.contains("SYNTHETIC_SENTINEL"));assertFalse(text.contains("example.invalid"));assertEquals(8,clean.content().get("stock"));
        assertTrue(clean.contentOmitted());assertTrue(clean.redactedFields().contains("title"));assertTrue(clean.redactedFields().contains("$unknown"));
    }
    @Test void strictNumbersEnumsAndBoundsRejectInsteadOfCoercing() {
        for(var input:List.of(Map.of("price","1.2"),Map.of("price",1.234),Map.of("price",-1),Map.of("stock",1.2),Map.of("stock",2147483648L),
                Map.of("skuCount",10001),Map.of("coverageStatus","VERIFIED"),Map.of("statusBucket","ALL"),Map.of("title",true),Map.of("skuSummary",Collections.nCopies(51,Map.of()))))
            assertThrows(BusinessException.class,()->safety.snapshot(input,true,false));
    }
    @Test void unknownNullRemainsUnknownAndDoesNotBecomeZero() {
        var map=new LinkedHashMap<String,Object>();map.put("price",null);map.put("stock",null);
        assertNull(safety.snapshot(map,true,false).content().get("price"));assertNull(safety.snapshot(map,true,false).content().get("stock"));
    }
    @Test void canonicalHashDoesNotDependOnMapOrderingAndSafeReplayIgnoresPrivateExtras() {
        var first=safety.snapshot(Map.of("title","QA","stock",3,"token","synthetic-one"),true,false);
        var second=safety.snapshot(Map.of("stock",3,"title","QA","password","synthetic-two"),true,false);
        assertEquals(safety.hash(first.content()),safety.hash(second.content()));
        assertEquals(safety.hash(Map.of("a",1,"b",2)),safety.hash(new LinkedHashMap<>(Map.of("b",2,"a",1))));
    }
    @Test void diffIsComputedFromSafeFieldsOnlyAndNoopIsExplicit() {
        var old=safety.snapshot(Map.of("title","old","stock",2),true,false).content();var next=safety.snapshot(Map.of("title","new","stock",2),true,false).content();
        assertEquals(List.of("title"),safety.diff(old,next).get("changedFields"));assertEquals(0,safety.diff(old,old).get("changedFieldCount"));
    }
    @Test void historicalLocalAndAutomationDiffKeepLegitimateChanges() {
        var input=Map.of("fields",Map.of("autoReplyEnabled",Map.of("before",true,"after",false,"label","自动回复"),"title",Map.of("before","旧标题","after","新标题"),"token",Map.of("before","sentinel","after","sentinel2")));
        var diff=safety.historicalDiff(input);var fields=(Map<?,?>)diff.get("fields");assertEquals(2,fields.size());assertTrue(fields.containsKey("autoReplyEnabled"));
        assertFalse(safety.canonical(diff).contains("sentinel"));assertTrue(safety.canonical(diff).contains("新标题"));
    }
    @Test void arbitraryHistoricalBodiesAndUnsafeErrorStringsAreNotReturned() {
        var content=safety.safeContent(Map.of("body","synthetic-private"),Map.of("secret",List.of("synthetic-private")),Map.of("message","synthetic-private"));
        assertFalse(safety.hasContent(content));assertFalse(safety.canonical(content).contains("synthetic-private"));assertNull(safety.safeText("Bearer synthetic-private"));
    }
    @Test void allowedLongPrivateTextAndDetailedLocationAreOmitted() {
        for(String text:List.of("私聊内容 synthetic-private","手机号13800138000","上海测试路123号","password=synthetic-private")) {
            assertFalse(safety.snapshot(Map.of("title",text),true,false).content().containsKey("title"));
        }
        assertEquals("上海",safety.snapshot(Map.of("location","上海"),true,false).content().get("location"));
    }
    @Test void signatureCanonicalNumbersDoNotInheritApplicationLongIdSerializers() {
        var mapper=new ObjectMapper();var module=new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class,com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);mapper.registerModule(module);
        var isolated=new ProductEventSafety(mapper);
        assertEquals("{\"timestamp\":1790920000000}",isolated.canonical(Map.of("timestamp",1790920000000L)));
        assertEquals("{\"price\":1000,\"stock\":8}",isolated.canonical(isolated.snapshot(Map.of("price",1000.00,"stock",8),true,false).content()));
    }
    @Test void historicalLocalEditLimitsKeepLegitimateLongTitlesAndPolicyDiff() {
        var before=Map.of("title","a".repeat(300),"supportPolicy","旧政策".repeat(200));
        var after=Map.of("title","b".repeat(300),"supportPolicy","新政策".repeat(200));
        var source=Map.of("fields",Map.of("title",Map.of("before",before.get("title"),"after",after.get("title")),"supportPolicy",Map.of("before",before.get("supportPolicy"),"after",after.get("supportPolicy"))));
        var diff=safety.historicalDiff(source);assertEquals(2,diff.get("changedFieldCount"));
        assertTrue(safety.canonical(diff).contains("b".repeat(300)));assertEquals(before,safety.snapshot(before,false,true).content());
        assertThrows(BusinessException.class,()->safety.snapshot(before,true,false));
    }
    @Test void credentialFormatsAndContactHintsCannotHideInsideAllowedFreeText() {
        for(String text:List.of("微信 synthetic-contact", "_m_h5_tk=synthetic-session", "eyJhbGciOiJIUzI1NiJ9.eyJwcml2YXRlIjoiUXEifQ.synthetic_signature", "sk-synthetic_secret_abcdefghijklmnop")) {
            var projected=safety.snapshot(Map.of("title",text),true,false);
            assertTrue(projected.contentOmitted());assertFalse(projected.content().containsKey("title"));assertEquals(List.of("title"),projected.redactedFields());
        }
    }
}
