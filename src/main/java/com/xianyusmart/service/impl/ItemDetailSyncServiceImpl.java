package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.controller.dto.ItemDTO;
import com.xianyusmart.controller.dto.SyncProgressRespDTO;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.entity.XianyuGoodsSkuProperty;
import com.xianyusmart.service.AccountAccessService;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.GoodsInfoService;
import com.xianyusmart.service.ItemDetailSyncService;
import com.xianyusmart.service.ItemDetailSnapshotService;
import com.xianyusmart.service.PlatformItemDetailFetchService;
import com.xianyusmart.utils.ItemDetailUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ItemDetailSyncServiceImpl implements ItemDetailSyncService {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountAccessService accountAccessService;

    @Autowired
    private GoodsInfoService goodsInfoService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformItemDetailFetchService platformItemDetailFetchService;

    @Autowired
    private ItemDetailSnapshotService itemDetailSnapshotService;

    private final ConcurrentHashMap<String, SyncProgress> progressMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> accountSyncMap = new ConcurrentHashMap<>();
    private static final int MAX_SKU_COUNT = 10_000;

    private static class SyncProgress {
        String syncId;
        Long accountId;
        int totalCount;
        int completedCount = 0;
        int successCount = 0;
        int failedCount = 0;
        boolean isCompleted = false;
        boolean isRunning = true;
        String currentItemId = null;
        String message = "同步中...";
        String lastFailureMessage = null;
        long startTime;
        boolean cancelled = false;
    }

    @Override
    public String startSync(Long accountId, List<ItemDTO> items) {
        if (!accountAccessService.canAccess(accountId)) {
            throw new IllegalArgumentException("当前成员无权访问该闲鱼账号");
        }
        if (isSyncing(accountId)) {
            String existingSyncId = accountSyncMap.get(accountId);
            log.info("账号已有同步任务进行中: accountId={}, syncId={}", accountId, existingSyncId);
            return existingSyncId;
        }

        String syncId = UUID.randomUUID().toString();
        SyncProgress progress = new SyncProgress();
        progress.syncId = syncId;
        progress.accountId = accountId;
        progress.totalCount = items.size();
        progress.startTime = System.currentTimeMillis();

        progressMap.put(syncId, progress);
        accountSyncMap.put(accountId, syncId);

        String cookieStr = accountService.getCookieByAccountId(accountId);

        executeSync(syncId, accountId, items, cookieStr);

        log.info("启动异步详情同步: syncId={}, accountId={}, itemCount={}", syncId, accountId, items.size());
        return syncId;
    }

    @Async
    public void executeSync(String syncId, Long accountId, List<ItemDTO> items, String cookieStr) {
        SyncProgress progress = progressMap.get(syncId);
        if (progress == null) {
            log.error("同步进度不存在: syncId={}", syncId);
            return;
        }

        try {
            for (ItemDTO item : items) {
                if (progress.cancelled) {
                    progress.message = "同步已取消";
                    break;
                }

                String itemId = item.getDetailParams() != null ? item.getDetailParams().getItemId() : item.getId();
                if (itemId == null || itemId.isEmpty()) {
                    progress.completedCount++;
                    progress.failedCount++;
                    continue;
                }

                progress.currentItemId = itemId;

                try {
                    Thread.sleep(new Random().nextInt(501));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                SyncResult syncResult = fetchAndSaveDetail(itemId, cookieStr, accountId);
                
                progress.completedCount++;
                if (syncResult.isSuccess()) {
                    progress.successCount++;
                } else {
                    progress.failedCount++;
                    progress.lastFailureMessage = syncResult.message();
                }

                progress.message = String.format("同步进度: %d/%d", progress.completedCount, progress.totalCount);
            }

            progress.isCompleted = true;
            progress.isRunning = false;
            progress.currentItemId = null;
            progress.message = String.format("同步完成: 成功%d, 失败%d%s",
                    progress.successCount, progress.failedCount,
                    progress.lastFailureMessage == null ? "" : "；最近失败: " + progress.lastFailureMessage);

        } catch (Exception e) {
            log.error("异步同步异常: syncId={}", syncId, e);
            progress.isCompleted = true;
            progress.isRunning = false;
            progress.message = "同步失败: " + e.getMessage();
        } finally {
            accountSyncMap.remove(accountId);
        }
    }

    private SyncResult fetchAndSaveDetail(String itemId, String cookieStr, Long accountId) {
        try {
            if (!accountAccessService.canAccess(accountId)) {
                return result(SyncStatus.FORBIDDEN, "当前成员无权访问该闲鱼账号", null);
            }
            if (goodsInfoService.getByXyGoodIdAndAccountId(itemId, accountId) == null) {
                return result(SyncStatus.NOT_FOUND, "商品不存在或不属于所选账号", null);
            }
            PlatformItemDetailFetchService.FetchResult fetchResult =
                    platformItemDetailFetchService.fetch(accountId, itemId, cookieStr);
            if (!fetchResult.isSuccess()) {
                log.warn("获取商品详情失败: accountId={}, itemId={}, status={}, reason={}",
                        accountId, itemId, fetchResult.status(), fetchResult.errorMessage());
                return switch (fetchResult.status()) {
                    case VERIFICATION_REQUIRED -> result(SyncStatus.VERIFICATION_REQUIRED,
                            fetchResult.errorMessage(), null);
                    case BUSY -> result(SyncStatus.BUSY, fetchResult.errorMessage(), null);
                    default -> result(SyncStatus.UNAVAILABLE, fetchResult.errorMessage(), null);
                };
            }
            String response = fetchResult.response();

            log.debug("商品详情响应已接收: itemId={}, source={}, length={}",
                    itemId, fetchResult.source(), response.length());
            JsonNode itemDONode = readSuccessfulItem(response, itemId);
            if (itemDONode == null) {
                log.warn("商品详情同步被平台拒绝: itemId={}", itemId);
                return result(SyncStatus.UNAVAILABLE, "平台详情响应无法验证", null);
            }

            JsonNode descNode = itemDONode.get("desc");
            if (descNode == null || !descNode.isTextual()) {
                return result(SyncStatus.UNAVAILABLE, "平台详情响应缺少可验证的详情字段", null);
            }

            JsonNode skuDataNode = ItemDetailUtils.findSkuListNode(itemDONode);
            if (!isCompleteSkuSnapshot(skuDataNode)) {
                return result(SyncStatus.UNAVAILABLE, "平台详情响应中的规格快照不完整，已保留旧数据", null);
            }
            List<XianyuGoodsSku> skuList = ItemDetailUtils.extractSkuList(response);
            if (skuList.size() != skuDataNode.size()) {
                return result(SyncStatus.UNAVAILABLE, "平台规格数据存在无效项，已保留旧数据", null);
            }
            List<XianyuGoodsSkuProperty> propertyList = ItemDetailUtils.extractSkuPropertyList(response);

            itemDetailSnapshotService.save(accountId, itemId, descNode.asText(), skuList, propertyList);

            log.info("商品详情同步成功: accountId={}, itemId={}, source={}, skuCount={}",
                    accountId, itemId, fetchResult.source(), skuList.size());
            return result(SyncStatus.SUCCESS, "同步成功", fetchResult.source().name());

        } catch (Exception e) {
            log.error("商品详情快照同步失败: accountId={}, itemId={}, errorType={}",
                    accountId, itemId, e.getClass().getSimpleName());
            return result(SyncStatus.UNAVAILABLE, "商品详情快照写入失败，已保留旧数据", null);
        }
    }

    private boolean isCompleteSkuSnapshot(JsonNode skuDataNode) {
        if (skuDataNode == null || !skuDataNode.isArray() || skuDataNode.size() > MAX_SKU_COUNT) {
            return false;
        }
        Set<String> skuIds = new HashSet<>();
        for (JsonNode skuNode : skuDataNode) {
            JsonNode skuIdNode = skuNode == null ? null : skuNode.get("skuId");
            if (skuNode == null || !skuNode.isObject()
                    || skuIdNode == null || !skuIdNode.isTextual()) {
                return false;
            }
            String skuId = skuIdNode.textValue().trim();
            if (skuId.isEmpty() || skuId.length() > 128 || !skuIds.add(skuId)) {
                return false;
            }
            JsonNode price = skuNode.get("price");
            JsonNode priceInCent = skuNode.get("priceInCent");
            if ((price == null || price.isNull()) && (priceInCent == null || priceInCent.isNull())) {
                return false;
            }
            if (!isOptionalNonNegativeInt(price)
                    || !isOptionalNonNegativeInt(priceInCent)
                    || !isRequiredNonNegativeInt(skuNode.get("quantity"))) {
                return false;
            }
            JsonNode propertyList = skuNode.get("propertyList");
            if (propertyList != null && !propertyList.isArray()) {
                return false;
            }
            if (propertyList != null) {
                for (JsonNode property : propertyList) {
                    if (!property.isObject()
                            || !isRequiredPositiveInt(property.get("propertyId"))
                            || !isRequiredPositiveInt(property.get("valueId"))
                            || !isOptionalNonNegativeInt(property.get("propertySortOrder"))
                            || !isOptionalNonNegativeInt(property.get("valueSortOrder"))
                            || !isOptionalText(property.get("propertyText"))
                            || !isOptionalText(property.get("valueText"))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean isRequiredNonNegativeInt(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.longValue() >= 0;
    }

    private boolean isRequiredPositiveInt(JsonNode value) {
        return isRequiredNonNegativeInt(value) && value.longValue() > 0;
    }

    private boolean isOptionalNonNegativeInt(JsonNode value) {
        return value == null || value.isNull() || isRequiredNonNegativeInt(value);
    }

    private boolean isOptionalText(JsonNode value) {
        return value == null || value.isNull() || value.isTextual();
    }

    private JsonNode readSuccessfulItem(String response, String itemId) {
        try {
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode retNode = rootNode.path("ret");
            if (!retNode.isArray() || retNode.isEmpty()
                    || !retNode.get(0).asText("").startsWith("SUCCESS")) {
                return null;
            }
            JsonNode itemDONode = rootNode.path("data").path("itemDO");
            if (!itemDONode.isObject()) {
                return null;
            }
            String responseItemId = itemDONode.path("itemId").asText("");
            if (responseItemId.isBlank()) {
                responseItemId = itemDONode.path("id").asText("");
            }
            return itemId.equals(responseItemId) ? itemDONode : null;
        } catch (Exception e) {
            log.warn("商品详情响应解析失败: errorType={}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean syncSingleItem(Long accountId, String itemId) {
        return syncSingleItemWithResult(accountId, itemId).isSuccess();
    }

    @Override
    public SyncResult syncSingleItemWithResult(Long accountId, String itemId) {
        if (accountId == null || itemId == null || itemId.isEmpty()) {
            log.warn("同步单个商品参数无效: accountId={}, itemId={}", accountId, itemId);
            return result(SyncStatus.UNAVAILABLE, "账号或商品 ID 为空", null);
        }
        if (!accountAccessService.canAccess(accountId)) {
            return result(SyncStatus.FORBIDDEN, "当前成员无权访问该闲鱼账号", null);
        }
        if (goodsInfoService.getByXyGoodIdAndAccountId(itemId, accountId) == null) {
            return result(SyncStatus.NOT_FOUND, "商品不存在或不属于所选账号", null);
        }
        String cookieStr = accountService.getCookieByAccountId(accountId);
        if (cookieStr == null || cookieStr.isEmpty()) {
            log.warn("账号Cookie不存在: accountId={}", accountId);
            return result(SyncStatus.VERIFICATION_REQUIRED,
                    "账号登录凭证不可用，请前往连接管理完成验证", null);
        }
        log.info("同步单个商品: accountId={}, itemId={}", accountId, itemId);
        return fetchAndSaveDetail(itemId, cookieStr, accountId);
    }

    private SyncResult result(SyncStatus status, String message, String source) {
        return new SyncResult(status, message == null || message.isBlank()
                ? "商品详情暂不可用" : message, source);
    }

    @Override
    public SyncProgressRespDTO getProgress(String syncId) {
        SyncProgress progress = progressMap.get(syncId);
        if (progress == null) {
            return null;
        }

        SyncProgressRespDTO dto = new SyncProgressRespDTO();
        dto.setSyncId(progress.syncId);
        dto.setAccountId(progress.accountId);
        dto.setTotalCount(progress.totalCount);
        dto.setCompletedCount(progress.completedCount);
        dto.setSuccessCount(progress.successCount);
        dto.setFailedCount(progress.failedCount);
        dto.setIsCompleted(progress.isCompleted);
        dto.setIsRunning(progress.isRunning);
        dto.setCurrentItemId(progress.currentItemId);
        dto.setMessage(progress.message);
        dto.setStartTime(progress.startTime);

        if (progress.completedCount > 0 && progress.totalCount > 0) {
            long elapsed = System.currentTimeMillis() - progress.startTime;
            long avgTimePerItem = elapsed / progress.completedCount;
            long remainingItems = progress.totalCount - progress.completedCount;
            dto.setEstimatedRemainingTime(avgTimePerItem * remainingItems);
        }

        return dto;
    }

    @Override
    public void cancelSync(String syncId) {
        SyncProgress progress = progressMap.get(syncId);
        if (progress != null) {
            progress.cancelled = true;
            progress.message = "正在取消同步...";
            log.info("取消同步: syncId={}", syncId);
        }
    }

    @Override
    public boolean isSyncing(Long accountId) {
        String syncId = accountSyncMap.get(accountId);
        if (syncId == null) {
            return false;
        }
        SyncProgress progress = progressMap.get(syncId);
        return progress != null && progress.isRunning && !progress.isCompleted;
    }
}
