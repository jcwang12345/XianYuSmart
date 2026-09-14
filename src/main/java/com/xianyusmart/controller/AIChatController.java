package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.controller.dto.ChatWithAIReqDTO;
import com.xianyusmart.controller.dto.DeleteRAGDataReqDTO;
import com.xianyusmart.controller.dto.PutNewDataToRAGReqDTO;
import com.xianyusmart.service.AIService;
import com.xianyusmart.service.GoodsKnowledgeService;
import com.xianyusmart.service.GoodsInfoService;
import com.xianyusmart.service.bo.RAGDataRespBO;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/**
 * AI对话控制器
 * 始终加载，AI功能未配置时自动降级
 *
 * @date 2026/4/12 00:16
 */
@RestController
@RequestMapping("/ai")
public class AIChatController {
    @Autowired
    private AIService aiService;

    @Autowired
    private DynamicAIChatClientManager dynamicAIChatClientManager;
    
    @Autowired
    private GoodsInfoService goodsInfoService;
    
    @Autowired
    private GoodsKnowledgeService goodsKnowledgeService;

    /**
     * AI对话（流式返回）
     * 未配置API Key时返回降级提示
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithAi(@RequestBody ChatWithAIReqDTO chatWithAIReqDTO) {
        return aiService.chatByRAG(chatWithAIReqDTO.getMsg(), chatWithAIReqDTO.getGoodsId());
    }

    /**
     * AI对话测试接口（流式）- 与自动回复流程一致
     * 携带固定资料和商品详情，用于测试提示词与资料效果
     */
    @PostMapping(path = "/chatTest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatTestWithAi(@RequestBody ChatTestReqDTO req) {
        String fixedMaterial = null;
        String goodsDetail = null;
        
        if (req.getAccountId() != null && req.getGoodsId() != null) {
            GoodsKnowledgeService.ActiveKnowledge knowledge = goodsKnowledgeService.effective(req.getAccountId(), req.getGoodsId());
            fixedMaterial = knowledge == null ? null : knowledge.content();
            
            String detailInfo = goodsInfoService.getDetailInfoByGoodsId(req.getGoodsId());
            if (detailInfo != null && !detailInfo.isEmpty()) {
                goodsDetail = detailInfo;
            }
        }
        
        return aiService.chatByRAGWithFixedMaterialStream(req.getMsg(), req.getGoodsId(), fixedMaterial, goodsDetail);
    }

    /**
     * AI状态检测接口
     * 返回AI服务是否可用、配置状态等信息
     */
    @PostMapping("/status")
    public ResultObject<AIStatusRespDTO> getAIStatus() {
        DynamicAIChatClientManager.AIStatusInfo statusInfo = dynamicAIChatClientManager.getStatusInfo();

        AIStatusRespDTO respDTO = new AIStatusRespDTO();
        respDTO.setEnabled(statusInfo.isEnabled());
        respDTO.setAvailable(statusInfo.isAvailable());
        respDTO.setApiKeyConfigured(statusInfo.isApiKeyConfigured());
        respDTO.setMessage(statusInfo.getMessage());
        respDTO.setBaseUrl(statusInfo.getBaseUrl());
        respDTO.setModel(statusInfo.getModel());
        respDTO.setProvider(statusInfo.getProvider());
        respDTO.setProtocol(statusInfo.getProtocol());
        respDTO.setEndpoint(statusInfo.getEndpoint());

        return ResultObject.success(respDTO);
    }

    @PostMapping("/putNewData")
    public ResultObject<?> putNewData(@RequestBody PutNewDataToRAGReqDTO putNewDataToRAGReqDTO) {
        try {
            aiService.putDataToRAG(putNewDataToRAGReqDTO.getContent(), putNewDataToRAGReqDTO.getGoodsId());
            return ResultObject.success(null);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("向量库未初始化")) {
                return ResultObject.failed(1001, "请先在系统设置的高级配置中启用并配置Embedding");
            }
            throw e;
        }
    }

    @PostMapping("/queryRAGData")
    public ResultObject<List<RAGDataRespBO>> queryRAGData(@RequestBody PutNewDataToRAGReqDTO req) {
        List<RAGDataRespBO> data = aiService.queryRAGDataBygoodsId(req.getGoodsId());
        return ResultObject.success(data);
    }

    @PostMapping("/deleteRAGData")
    public ResultObject<?> deleteRAGData(@RequestBody DeleteRAGDataReqDTO req) {
        aiService.deleteRAGDataByDocumentId(req.getDocumentId());
        return ResultObject.success(null);
    }

    @PostMapping("/saveFixedMaterial")
    public ResultObject<Map<String, Object>> saveFixedMaterial(@RequestBody FixedMaterialReqDTO req) {
        return ResultObject.success(goodsKnowledgeService.save(new GoodsKnowledgeService.SaveCommand(
                req.getAccountId(), req.getGoodsId(), req.getFixedMaterial(), req.getEffectiveTime(),
                req.getExpiresTime(), req.getActivate(), "MANUAL", req.getRequestId())));
    }

    @PostMapping("/getFixedMaterial")
    public ResultObject<Map<String, Object>> getFixedMaterial(@RequestBody FixedMaterialReqDTO req) {
        return ResultObject.success(goodsKnowledgeService.view(req.getAccountId(), req.getGoodsId()));
    }

    @PostMapping("/syncDetailToFixedMaterial")
    public ResultObject<?> syncDetailToFixedMaterial(@RequestBody FixedMaterialReqDTO req) {
        String detailInfo = goodsInfoService.getDetailInfoByGoodsId(req.getGoodsId());
        if (detailInfo != null && !detailInfo.isEmpty()) {
            return ResultObject.success(goodsKnowledgeService.save(new GoodsKnowledgeService.SaveCommand(
                    req.getAccountId(), req.getGoodsId(), detailInfo, LocalDateTime.now(), req.getExpiresTime(),
                    true, "GOODS_DETAIL", req.getRequestId())));
        } else {
            return ResultObject.failed("商品详情为空，无法同步");
        }
    }

    @PostMapping("/activateFixedMaterialVersion")
    public ResultObject<Map<String, Object>> activateFixedMaterialVersion(@RequestBody FixedMaterialVersionActionReqDTO req) {
        return ResultObject.success(goodsKnowledgeService.activate(req.getVersionId(), req.getRequestId()));
    }

    @PostMapping("/expireFixedMaterialVersion")
    public ResultObject<Map<String, Object>> expireFixedMaterialVersion(@RequestBody FixedMaterialVersionActionReqDTO req) {
        return ResultObject.success(goodsKnowledgeService.expire(req.getVersionId(), req.getRequestId()));
    }

    public static class FixedMaterialReqDTO {
        private Long accountId;
        private String goodsId;
        private String fixedMaterial;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss][.SSS]")
        private LocalDateTime effectiveTime;
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss][.SSS]")
        private LocalDateTime expiresTime;
        private Boolean activate;
        private String requestId;

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public String getGoodsId() { return goodsId; }
        public void setGoodsId(String goodsId) { this.goodsId = goodsId; }
        public String getFixedMaterial() { return fixedMaterial; }
        public void setFixedMaterial(String fixedMaterial) { this.fixedMaterial = fixedMaterial; }
        public LocalDateTime getEffectiveTime() { return effectiveTime; }
        public void setEffectiveTime(LocalDateTime effectiveTime) { this.effectiveTime = effectiveTime; }
        public LocalDateTime getExpiresTime() { return expiresTime; }
        public void setExpiresTime(LocalDateTime expiresTime) { this.expiresTime = expiresTime; }
        public Boolean getActivate() { return activate; }
        public void setActivate(Boolean activate) { this.activate = activate; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
    }

    public static class FixedMaterialVersionActionReqDTO {
        private Long versionId;
        private String requestId;
        public Long getVersionId() { return versionId; }
        public void setVersionId(Long versionId) { this.versionId = versionId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
    }

    public static class ChatTestReqDTO {
        private Long accountId;
        private String goodsId;
        private String msg;

        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        public String getGoodsId() { return goodsId; }
        public void setGoodsId(String goodsId) { this.goodsId = goodsId; }
        public String getMsg() { return msg; }
        public void setMsg(String msg) { this.msg = msg; }
    }

    /**
     * AI状态响应DTO
     */
    public static class AIStatusRespDTO {
        private boolean enabled;
        private boolean available;
        private boolean apiKeyConfigured;
        private String message;
        private String baseUrl;
        private String model;
        private String provider;
        private String protocol;
        private String endpoint;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public boolean isApiKeyConfigured() { return apiKeyConfigured; }
        public void setApiKeyConfigured(boolean apiKeyConfigured) { this.apiKeyConfigured = apiKeyConfigured; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }
}
