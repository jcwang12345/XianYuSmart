package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.BuyerProfileQueryReqDTO;
import com.xianyusmart.controller.dto.BuyerMessageDTO;
import com.xianyusmart.controller.dto.BuyerProfileDetailReqDTO;
import com.xianyusmart.controller.dto.BuyerProfileDetailRespDTO;
import com.xianyusmart.controller.dto.BuyerProfileRespDTO;
import com.xianyusmart.controller.dto.BuyerProfileSaveReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuBuyerProfile;
import com.xianyusmart.entity.XianyuBuyerProfileRequest;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuBuyerProfileMapper;
import com.xianyusmart.mapper.XianyuBuyerProfileRequestMapper;
import com.xianyusmart.mapper.XianyuChatMessageMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.service.buyer.BuyerProfilePolicy;
import com.xianyusmart.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 买家关系与自动化拦截服务
 */
@Service
public class BuyerProfileService {

    private final XianyuBuyerProfileMapper profileMapper;
    private final XianyuAccountMapper accountMapper;
    private final ObjectMapper objectMapper;
    private final XianyuGoodsOrderMapper orderMapper;
    private final XianyuChatMessageMapper messageMapper;
    private final XianyuBuyerProfileRequestMapper requestMapper;
    private final OperationLogService operationLogService;

    public BuyerProfileService(XianyuBuyerProfileMapper profileMapper,
                               XianyuAccountMapper accountMapper,
                               ObjectMapper objectMapper,
                               XianyuGoodsOrderMapper orderMapper,
                               XianyuChatMessageMapper messageMapper,
                               XianyuBuyerProfileRequestMapper requestMapper,
                               OperationLogService operationLogService) {
        this.profileMapper = profileMapper;
        this.accountMapper = accountMapper;
        this.objectMapper = objectMapper;
        this.orderMapper = orderMapper;
        this.messageMapper = messageMapper;
        this.requestMapper = requestMapper;
        this.operationLogService = operationLogService;
    }

    public Map<String, Object> list(BuyerProfileQueryReqDTO request) {
        validateOwnedAccount(request.getXianyuAccountId(), false);
        int pageNum = request.getPageNum() == null ? 1 : Math.max(1, request.getPageNum());
        int pageSize = request.getPageSize() == null ? 30 : Math.max(1, Math.min(100, request.getPageSize()));
        Integer blocked = request.getAutomationBlocked() == null ? null
                : (request.getAutomationBlocked() ? 1 : 0);
        String keyword = trimToNull(request.getKeyword());
        List<BuyerProfileRespDTO> records = profileMapper.selectPage(
                request.getXianyuAccountId(), keyword, blocked, pageSize, (long) (pageNum - 1) * pageSize);
        records.forEach(this::readTags);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", profileMapper.countPage(request.getXianyuAccountId(), keyword, blocked));
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    @Transactional
    public BuyerProfileRespDTO save(BuyerProfileSaveReqDTO request) {
        validateOwnedAccount(request.getXianyuAccountId(), true);
        Long tenantId = requireTenantId();
        String buyerUserId = request.getBuyerUserId().trim();
        String requestId = request.getRequestId().trim();
        String idempotencyKey = trimToNull(request.getIdempotencyKey());
        if (idempotencyKey == null) {
            idempotencyKey = requestId;
        }
        List<String> normalizedTags = BuyerProfilePolicy.normalizeTags(request.getTags());
        String fingerprint = requestFingerprint(request, buyerUserId, normalizedTags);
        int reserved = requestMapper.reserve(tenantId, request.getXianyuAccountId(), buyerUserId,
                requestId, idempotencyKey, fingerprint);
        if (reserved == 0) {
            return replayExistingRequest(tenantId, requestId, fingerprint);
        }

        XianyuBuyerProfile profile = profileMapper.findByBuyer(request.getXianyuAccountId(), buyerUserId);
        Map<String, Object> before = profileSnapshot(profile);
        if (profile == null) {
            profile = new XianyuBuyerProfile();
            profile.setTenantId(tenantId);
            profile.setXianyuAccountId(request.getXianyuAccountId());
            profile.setBuyerUserId(buyerUserId);
        }
        String buyerUserName = trimToNull(request.getBuyerUserName());
        String note = trimToNull(request.getNote());
        String blockedReason = trimToNull(request.getBlockedReason());
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("买家备注不能超过500个字符");
        }
        if (blockedReason != null && blockedReason.length() > 200) {
            throw new IllegalArgumentException("拦截原因不能超过200个字符");
        }
        profile.setBuyerUserName(buyerUserName);
        profile.setTagsJson(writeTags(normalizedTags));
        profile.setNote(note);
        boolean blacklisted = request.getBlacklisted() == null
                ? Integer.valueOf(1).equals(profile.getBlacklisted())
                : Boolean.TRUE.equals(request.getBlacklisted());
        boolean automationBlocked = blacklisted || Boolean.TRUE.equals(request.getAutomationBlocked());
        if (blacklisted && blockedReason == null) {
            blockedReason = "[买家] 已加入客户黑名单";
        } else if (!blacklisted && (blockedReason != null)
                && (blockedReason.startsWith("[买家]") || blockedReason.startsWith("[会话]"))) {
            blockedReason = null;
        }
        boolean blacklistChanged = !Objects.equals(
                Integer.valueOf(blacklisted ? 1 : 0), profile.getBlacklisted());
        profile.setAutomationBlocked(automationBlocked ? 1 : 0);
        profile.setBlockedReason(blockedReason);
        profile.setBlacklisted(blacklisted ? 1 : 0);
        if (blacklistChanged) {
            profile.setBlacklistSource("BUYER_360");
            profile.setBlacklistUpdatedTime(LocalDateTime.now());
        }
        if (profile.getId() == null) {
            profileMapper.insert(profile);
        } else {
            profileMapper.updateById(profile);
        }
        profileMapper.updateAutomationAndBlacklist(tenantId, profile.getXianyuAccountId(),
                profile.getBuyerUserId(), profile.getAutomationBlocked(), profile.getBlockedReason(),
                profile.getBlacklisted(), profile.getBlacklistSource(), profile.getBlacklistUpdatedTime());
        profileMapper.updateConversationBlacklist(tenantId, profile.getXianyuAccountId(),
                profile.getBuyerUserId(), blacklisted ? 1 : 0);
        BuyerProfileRespDTO response = profileMapper.selectDetail(profile.getXianyuAccountId(), profile.getBuyerUserId());
        if (response != null) {
            readTags(response);
        }
        Map<String, Object> after = profileSnapshot(profile);
        Map<String, Object> fieldDiff = fieldDiff(before, after);
        writeRequiredAudit(request, requestId, idempotencyKey, buyerUserId, before, after, fieldDiff);
        if (requestMapper.complete(tenantId, requestId, writeJson(response)) != 1) {
            throw new IllegalStateException("买家资料请求结果保存失败");
        }
        return response;
    }

    private BuyerProfileRespDTO replayExistingRequest(Long tenantId, String requestId, String fingerprint) {
        XianyuBuyerProfileRequest existing = requestMapper.findByRequestId(tenantId, requestId);
        if (existing == null || !Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
            throw new BusinessException(409, "requestId已用于其他买家资料变更");
        }
        if (existing.getResponseJson() == null) {
            throw new BusinessException(409, "该买家资料请求正在处理中，请稍后按原请求重试");
        }
        try {
            return objectMapper.readValue(existing.getResponseJson(), BuyerProfileRespDTO.class);
        } catch (Exception e) {
            throw new IllegalStateException("买家资料幂等结果读取失败", e);
        }
    }

    private void writeRequiredAudit(BuyerProfileSaveReqDTO request,
                                    String requestId,
                                    String idempotencyKey,
                                    String buyerUserId,
                                    Map<String, Object> before,
                                    Map<String, Object> after,
                                    Map<String, Object> fieldDiff) {
        Map<String, Object> requestParams = new LinkedHashMap<>();
        requestParams.put("accountId", request.getXianyuAccountId());
        requestParams.put("buyerUserId", buyerUserId);
        requestParams.put("before", before);
        requestParams.put("requestedChanges", fieldDiff);
        Map<String, Object> responseResult = new LinkedHashMap<>();
        responseResult.put("after", after);
        responseResult.put("fieldDiff", fieldDiff);
        responseResult.put("replayed", false);

        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(request.getXianyuAccountId());
        audit.setOperationType("BUYER_PROFILE_UPDATE");
        audit.setOperationModule("买家管理");
        audit.setOperationDesc(fieldDiff.isEmpty() ? "买家资料未发生变化" : "更新买家360资料与自动化范围");
        audit.setOperationStatus(1);
        audit.setTargetType("BUYER");
        audit.setTargetId(buyerUserId);
        audit.setRequestParams(writeJson(requestParams));
        audit.setResponseResult(writeJson(responseResult));
        audit.setRequestId(requestId);
        audit.setIdempotencyKey(idempotencyKey);
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        audit.setFieldDiffJson(writeJson(fieldDiff));
        operationLogService.logRequired(audit);
    }

    private String requestFingerprint(BuyerProfileSaveReqDTO request,
                                      String buyerUserId,
                                      List<String> normalizedTags) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("accountId", request.getXianyuAccountId());
        normalized.put("buyerUserId", buyerUserId);
        normalized.put("buyerUserName", trimToNull(request.getBuyerUserName()));
        normalized.put("tags", normalizedTags);
        normalized.put("note", trimToNull(request.getNote()));
        normalized.put("automationBlocked", request.getAutomationBlocked());
        normalized.put("blockedReason", trimToNull(request.getBlockedReason()));
        normalized.put("blacklisted", request.getBlacklisted());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(writeJson(normalized).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("买家资料请求摘要生成失败", e);
        }
    }

    private Map<String, Object> profileSnapshot(XianyuBuyerProfile profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("buyerUserName", profile == null ? null : profile.getBuyerUserName());
        snapshot.put("tags", profile == null ? List.of() : readTagsJson(profile.getTagsJson()));
        snapshot.put("note", profile == null ? null : profile.getNote());
        snapshot.put("automationBlocked", profile == null ? null : Integer.valueOf(1).equals(profile.getAutomationBlocked()));
        snapshot.put("blockedReason", profile == null ? null : profile.getBlockedReason());
        snapshot.put("blacklisted", profile == null ? null : Integer.valueOf(1).equals(profile.getBlacklisted()));
        snapshot.put("blacklistSource", profile == null ? null : profile.getBlacklistSource());
        snapshot.put("blacklistUpdatedTime", profile == null || profile.getBlacklistUpdatedTime() == null
                ? null : profile.getBlacklistUpdatedTime().toString());
        return snapshot;
    }

    private Map<String, Object> fieldDiff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> diff = new LinkedHashMap<>();
        after.forEach((field, afterValue) -> {
            Object beforeValue = before.get(field);
            if (!Objects.equals(beforeValue, afterValue)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", beforeValue);
                change.put("after", afterValue);
                diff.put(field, change);
            }
        });
        return diff;
    }

    private List<String> readTagsJson(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() { });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("买家资料审计序列化失败", e);
        }
    }

    public void touch(Long accountId, String buyerUserId, String buyerUserName, Long messageTime) {
        if (accountId == null || buyerUserId == null || buyerUserId.isBlank()) {
            return;
        }
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            return;
        }
        LocalDateTime interactionTime = messageTime == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(messageTime), ZoneId.systemDefault());
        profileMapper.touch(account.getTenantId(), accountId, buyerUserId.trim(), trimToNull(buyerUserName), interactionTime);
    }

    public String automationBlockReason(Long accountId, String buyerUserId) {
        if (accountId == null || buyerUserId == null || buyerUserId.isBlank()) {
            return null;
        }
        XianyuBuyerProfile profile = profileMapper.findByBuyer(accountId, buyerUserId);
        if (profile == null) {
            return null;
        }
        return BuyerProfilePolicy.blockReason(
                Integer.valueOf(1).equals(profile.getAutomationBlocked()), profile.getBlockedReason());
    }

    public BuyerProfileDetailRespDTO detail(BuyerProfileDetailReqDTO request) {
        validateOwnedAccount(request.getXianyuAccountId(), true);
        String buyerUserId = request.getBuyerUserId().trim();
        BuyerProfileRespDTO profile = profileMapper.selectDetail(request.getXianyuAccountId(), buyerUserId);
        if (profile == null) {
            throw new IllegalArgumentException("买家资料不存在");
        }
        readTags(profile);
        List<com.xianyusmart.entity.XianyuGoodsOrder> orders =
                orderMapper.selectByBuyer(request.getXianyuAccountId(), buyerUserId);
        Map<String, List<com.xianyusmart.entity.XianyuGoodsOrder>> sessionOrders = new LinkedHashMap<>();
        orders.forEach(order -> {
            if (order.getSid() == null || order.getSid().isBlank()
                    || order.getOrderId() == null || order.getOrderId().isBlank()) {
                return;
            }
            List<com.xianyusmart.entity.XianyuGoodsOrder> sessionOrderList = sessionOrders.computeIfAbsent(order.getSid(),
                    ignored -> new java.util.ArrayList<>());
            sessionOrderList.add(order);
        });
        List<BuyerMessageDTO> messages = messageMapper
                .findByBuyerAndSessions(request.getXianyuAccountId(), buyerUserId)
                .stream()
                .map(message -> toMessage(message, buyerUserId, sessionOrders))
                .toList();
        BuyerProfileDetailRespDTO detail = new BuyerProfileDetailRespDTO();
        detail.setProfile(profile);
        detail.setOrders(orders);
        detail.setMessages(messages);
        detail.setGoods(profileMapper.selectRelatedGoods(request.getXianyuAccountId(), buyerUserId));
        return detail;
    }

    private void validateOwnedAccount(Long accountId, boolean required) {
        if (accountId == null) {
            if (required) {
                throw new IllegalArgumentException("闲鱼账号ID不能为空");
            }
            return;
        }
        if (accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("闲鱼账号不存在或无权访问");
        }
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("登录状态已失效");
        }
        return tenantId;
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new IllegalArgumentException("买家标签格式无效");
        }
    }

    private void readTags(BuyerProfileRespDTO profile) {
        try {
            profile.setTags(profile.getTagsJson() == null
                    ? List.of()
                    : objectMapper.readValue(profile.getTagsJson(), new TypeReference<>() {
                    }));
        } catch (Exception e) {
            profile.setTags(List.of());
        }
    }

    private BuyerMessageDTO toMessage(com.xianyusmart.entity.XianyuChatMessage message,
                                      String buyerUserId,
                                      Map<String, List<com.xianyusmart.entity.XianyuGoodsOrder>> sessionOrders) {
        BuyerMessageDTO response = new BuyerMessageDTO();
        response.setId(message.getId());
        response.setPnmId(message.getPnmId());
        response.setSid(message.getSId());
        response.setContentType(message.getContentType());
        response.setContent(message.getMsgContent());
        response.setSenderUserName(message.getSenderUserName());
        response.setSenderUserId(message.getSenderUserId());
        response.setXyGoodsId(message.getXyGoodsId());
        response.setMessageTime(message.getMessageTime());
        response.setCreateTime(message.getCreateTime());
        response.setDirection(buyerUserId.equals(message.getSenderUserId()) ? "BUYER" : "SELLER");
        response.setRelatedOrderIds(resolveRelatedOrderIds(message,
                sessionOrders.getOrDefault(message.getSId(), List.of())));
        return response;
    }

    private List<String> resolveRelatedOrderIds(
            com.xianyusmart.entity.XianyuChatMessage message,
            List<com.xianyusmart.entity.XianyuGoodsOrder> sessionOrders) {
        if (sessionOrders.isEmpty()) {
            return List.of();
        }
        List<com.xianyusmart.entity.XianyuGoodsOrder> candidates = sessionOrders;
        if (message.getPnmId() != null && !message.getPnmId().isBlank()) {
            List<com.xianyusmart.entity.XianyuGoodsOrder> pnmMatches = sessionOrders.stream()
                    .filter(order -> message.getPnmId().equals(order.getPnmId()))
                    .toList();
            if (!pnmMatches.isEmpty()) {
                candidates = pnmMatches;
            }
        }
        if (candidates == sessionOrders && message.getXyGoodsId() != null && !message.getXyGoodsId().isBlank()) {
            List<com.xianyusmart.entity.XianyuGoodsOrder> goodsMatches = sessionOrders.stream()
                    .filter(order -> message.getXyGoodsId().equals(order.getXyGoodsId()))
                    .toList();
            if (!goodsMatches.isEmpty()) {
                candidates = goodsMatches;
            }
        }
        long messageTimestamp = message.getMessageTime() == null
                ? (message.getCreateTime() == null ? 0L
                    : message.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                : message.getMessageTime();
        // 同会话多订单时按消息标识、商品和时间选出最相关订单，避免整段会话重复归到每一单。
        com.xianyusmart.entity.XianyuGoodsOrder relatedOrder = candidates.stream()
                .min(java.util.Comparator.comparingLong(order ->
                        timeDistance(messageTimestamp, orderTimestamp(order))))
                .orElse(candidates.getFirst());
        return relatedOrder.getOrderId() == null ? List.of() : List.of(relatedOrder.getOrderId());
    }

    private long orderTimestamp(com.xianyusmart.entity.XianyuGoodsOrder order) {
        String value = order.getOrderCreateTime() == null || order.getOrderCreateTime().isBlank()
                ? order.getCreateTime()
                : order.getOrderCreateTime();
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(value.trim().replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long timeDistance(long messageTimestamp, long orderTimestamp) {
        if (messageTimestamp <= 0 || orderTimestamp <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.abs(messageTimestamp - orderTimestamp);
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
