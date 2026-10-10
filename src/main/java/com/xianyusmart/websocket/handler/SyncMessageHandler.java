package com.xianyusmart.websocket.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuChatMessage;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.utils.MessageDecryptUtils;
import org.springframework.beans.BeanUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同步包消息处理器
 * 处理 /s/para 和 /s/sync 类型的聊天消息
 * 
 * <p>职责：</p>
 * <ul>
 *   <li>解密同步包中的加密消息</li>
 *   <li>解析消息字段，构建 XianyuChatMessage 对象</li>
 *   <li>发布 ChatMessageReceivedEvent 事件</li>
 * </ul>
 */
@Slf4j
@Component
public class SyncMessageHandler extends AbstractLwpHandler {
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired
    private LiveSyncMessageParser liveSyncMessageParser;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public String getLwpPath() {
        return "/s/para"; // 也支持 /s/sync
    }
    
    @Override
    protected Object parseParams(String accountId, Map<String, Object> messageData) {
        try {
            SyncMessageParams params = new SyncMessageParams();
            
            // 获取body
            Map<String, Object> body = getMap(messageData, "body");
            if (body == null) {
                return null;
            }
            
            // 获取syncPushPackage
            Map<String, Object> syncPushPackage = getMap(body, "syncPushPackage");
            if (syncPushPackage == null) {
                return null;
            }
            
            // 获取data列表
            List<Object> dataList = getList(syncPushPackage, "data");
            if (dataList == null || dataList.isEmpty()) {
                return null;
            }
            
            params.setDataList(dataList);
            params.setMessageCount(dataList.size());
            
            return params;
            
        } catch (Exception e) {
            log.error("【账号{}】解析同步包参数失败", accountId, e);
            return null;
        }
    }
    
    @Override
    protected Object doHandle(String accountId, Object params, Map<String, Object> messageData) {
        SyncMessageParams syncParams = (SyncMessageParams) params;
        List<String> decryptedMessages = new ArrayList<>();
        
        // 获取lwp字段
        String lwp = getString(messageData, "lwp");
        
        log.info("【账号{}】收到闲鱼原始消息: lwp={}, messageCount={}", accountId, lwp, syncParams.getMessageCount());
        
        // 处理每条加密消息
        for (int i = 0; i < syncParams.getDataList().size(); i++) {
            Object item = syncParams.getDataList().get(i);
            if (!(item instanceof Map)) {
                continue;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> syncData = (Map<String, Object>) item;
            
            String encryptedData = getString(syncData, "data");
            if (encryptedData == null) {
                continue;
            }
            
            log.debug("【账号{}】收到加密消息[{}]，长度={}", accountId, i, encryptedData.length());
            
            // 解密消息
            String decryptedData = getString(syncData, "decryptedData");
            if (decryptedData == null) {
                decryptedData = MessageDecryptUtils.decrypt(encryptedData);
            }
            if (decryptedData != null) {
                log.debug("【账号{}】消息[{}]解密成功，长度={}", accountId, i, decryptedData.length());
                decryptedMessages.add(decryptedData);
                
                // 解析并发布事件
                parseAndPublishEvent(accountId, decryptedData, lwp);
            } else {
                log.warn("【账号{}】消息解密失败[{}]", accountId, i);
            }
        }
        
        return decryptedMessages;
    }
    
    @Override
    protected void postHandle(String accountId, Object result, Map<String, Object> messageData) {
        // 处理完成，不需要额外日志
    }
    
    /**
     * 解析消息并发布事件
     * 
     * @param accountId 账号ID
     * @param decryptedData 解密后的JSON数据
     * @param lwp WebSocket消息路径
     */
    private void parseAndPublishEvent(String accountId, String decryptedData, String lwp) {
        LiveSyncMessageParser.ParseResult result = liveSyncMessageParser.parse(
                Long.parseLong(accountId), lwp, decryptedData);
        if (!result.parsed()) {
            // Keep classification failures observable without writing chat bodies
            // or credentials to production logs.
            log.info("【账号{}】同步消息未生成业务事件: lwp={}, reason={}, decodedLength={}, shape={}",
                    accountId, lwp, result.ignoredReason(), decryptedData.length(),
                    "UNSUPPORTED_RECORD_SHAPE".equals(result.ignoredReason())
                            ? payloadShape(decryptedData) : "not-applicable");
            return;
        }
        publishChatMessageReceivedEvent(result.message());
    }

    /** Log only protocol field names and value types; never chat text or IDs. */
    private String payloadShape(String decryptedData) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(decryptedData, Map.class);
            StringBuilder shape = new StringBuilder();
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                if (!shape.isEmpty()) shape.append(',');
                shape.append(entry.getKey()).append(':');
                if (entry.getValue() instanceof Map<?, ?> nested) {
                    shape.append("map").append(nested.keySet());
                } else if (entry.getValue() instanceof List<?> list) {
                    shape.append("list(").append(list.size()).append(')');
                } else if (entry.getValue() instanceof String value) {
                    shape.append("string(").append(value.length()).append(')');
                } else {
                    shape.append(entry.getValue() == null ? "null" : entry.getValue().getClass().getSimpleName());
                }
            }
            return shape.substring(0, Math.min(shape.length(), 240));
        } catch (Exception e) {
            return "invalid-json";
        }
    }
    
    /**
     * 发布聊天消息接收事件
     */
    private void publishChatMessageReceivedEvent(XianyuChatMessage message) {
        try {
            // 转换为 ChatMessageData
            ChatMessageData messageData = new ChatMessageData();
            BeanUtils.copyProperties(message, messageData);
            
            // 从完整消息中提取订单ID
            String orderId = extractOrderIdFromMessage(message.getCompleteMsg());
            messageData.setOrderId(orderId);
            
            log.info("【账号{}】准备发布ChatMessageReceivedEvent事件: pnmId={}, contentType={}, hasGoodsId={}, hasOrderId={}",
                    message.getXianyuAccountId(), message.getPnmId(), message.getContentType(),
                    message.getXyGoodsId() != null, orderId != null);
            
            ChatMessageReceivedEvent event = new ChatMessageReceivedEvent(this, messageData);
            eventPublisher.publishEvent(event);
            
            log.info("【账号{}】ChatMessageReceivedEvent事件已发布: pnmId={}, orderId={}", 
                    message.getXianyuAccountId(), message.getPnmId(), orderId);
        } catch (Exception e) {
            log.error("【账号{}】发布消息接收事件失败: pnmId={}", 
                    message.getXianyuAccountId(), message.getPnmId(), e);
        }
    }
    
    /**
     * 从完整消息中提取订单ID
     */
    private String extractOrderIdFromMessage(String completeMsg) {
        try {
            if (completeMsg == null || completeMsg.isEmpty()) {
                log.debug("📋 提取订单ID: 消息为空");
                return null;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(completeMsg, Map.class);

            String directOrderId = findOrderId(data, 0);
            if (directOrderId != null) {
                return directOrderId;
            }
            
            // 从 1.6.3.5 中提取订单ID
            // 路径: 1.6.3.5 (这是一个JSON字符串，需要再次解析)
            Object level1 = data.get("1");
            if (level1 instanceof Map) {
                Object level6 = ((Map<?, ?>) level1).get("6");
                if (level6 instanceof Map) {
                    Object level3 = ((Map<?, ?>) level6).get("3");
                    if (level3 instanceof Map) {
                        Object level5 = ((Map<?, ?>) level3).get("5");
                        if (level5 instanceof String) {
                            String jsonStr = (String) level5;
                            log.debug("📋 已从字段1.6.3.5提取订单信息，载荷长度={}", jsonStr.length());
                            
                            try {
                                // 解析嵌套的JSON字符串
                                @SuppressWarnings("unchecked")
                                Map<String, Object> contentMap = objectMapper.readValue(jsonStr, Map.class);
                                
                                // 方法1: 从 dxCard.item.main.exContent.button.targetUrl 中提取
                                String orderId = extractOrderIdFromDxCard(contentMap);
                                if (orderId != null) {
                                    return orderId;
                                }
                                
                                // 方法2: 从 dynamicOperation.changeContent.dxCard.item.main.exContent.button.targetUrl 中提取（旧方法）
                                orderId = extractOrderIdFromDynamicOperation(contentMap);
                                if (orderId != null) {
                                    return orderId;
                                }
                                
                            } catch (Exception e) {
                                log.warn("⚠️ 解析字段1.6.3.5的JSON失败", e);
                            }
                        } else {
                            log.debug("⚠️ 字段1.6.3.5不是字符串类型");
                        }
                    } else {
                        log.debug("⚠️ 字段1.6.3不存在或不是Map类型");
                    }
                } else {
                    log.debug("⚠️ 字段1.6不存在或不是Map类型");
                }
            } else {
                log.debug("⚠️ 字段1不存在或不是Map类型");
            }
            
            return null;
        } catch (Exception e) {
            log.error("❌ 提取订单ID失败", e);
            return null;
        }
    }
    
    /**
     * 从 dxCard.item.main.exContent.button.targetUrl 中提取订单ID
     * 新方法：适用于 contentType=26 的消息
     */
    private String extractOrderIdFromDxCard(Map<String, Object> contentMap) {
        try {
            Object dxCard = contentMap.get("dxCard");
            if (dxCard instanceof Map) {
                Object item = ((Map<?, ?>) dxCard).get("item");
                if (item instanceof Map) {
                    Object main = ((Map<?, ?>) item).get("main");
                    if (main instanceof Map) {
                        Object exContent = ((Map<?, ?>) main).get("exContent");
                        if (exContent instanceof Map) {
                            Object button = ((Map<?, ?>) exContent).get("button");
                            if (button instanceof Map) {
                                String targetUrl = (String) ((Map<?, ?>) button).get("targetUrl");
                                if (targetUrl != null) {
                                    return extractOrderIdFromUrl(targetUrl);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从dxCard提取订单ID失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 从 dynamicOperation.changeContent.dxCard.item.main.exContent.button.targetUrl 中提取订单ID
     * 旧方法：适用于 contentType=32 的消息
     */
    private String extractOrderIdFromDynamicOperation(Map<String, Object> contentMap) {
        try {
            Object dynamicOp = contentMap.get("dynamicOperation");
            if (dynamicOp instanceof Map) {
                Object changeContent = ((Map<?, ?>) dynamicOp).get("changeContent");
                if (changeContent instanceof Map) {
                    Object dxCard = ((Map<?, ?>) changeContent).get("dxCard");
                    if (dxCard instanceof Map) {
                        Object item = ((Map<?, ?>) dxCard).get("item");
                        if (item instanceof Map) {
                            Object main = ((Map<?, ?>) item).get("main");
                            if (main instanceof Map) {
                                Object exContent = ((Map<?, ?>) main).get("exContent");
                                if (exContent instanceof Map) {
                                    Object button = ((Map<?, ?>) exContent).get("button");
                                    if (button instanceof Map) {
                                        String targetUrl = (String) ((Map<?, ?>) button).get("targetUrl");
                                        if (targetUrl != null) {
                                            return extractOrderIdFromUrl(targetUrl);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从dynamicOperation提取订单ID失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 从URL中提取订单ID
     * 支持两种格式：
     * 1. orderId=4502258607179022847
     * 2. id=4502258607179022847
     */
    private String extractOrderIdFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        String orderId = extractQueryValue(url, "orderId");
        return orderId != null ? orderId : extractQueryValue(url, "id");
    }

    private String extractQueryValue(String value, String key) {
        int start = -1;
        for (String prefix : List.of("?", "&", "#", "")) {
            int candidate = value.indexOf(prefix + key + "=");
            if (candidate >= 0 && (prefix.length() > 0 || candidate == 0)) {
                start = candidate + prefix.length() + key.length() + 1;
                break;
            }
        }
        if (start < 0) return null;
        int end = start;
        while (end < value.length() && isOrderIdCharacter(value.charAt(end))) end++;
        if (end == start || end - start > 100) return null;
        return value.substring(start, end);
    }

    private boolean isOrderIdCharacter(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value == '_' || value == '-';
    }

    private String findOrderId(Object value, int depth) {
        if (value == null || depth > 10) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("orderId", "tradeId", "mainOrderId")) {
                Object direct = map.get(key);
                if (direct != null && !direct.toString().isBlank()) {
                    String candidate = direct.toString();
                    if (candidate.length() <= 100 && candidate.chars().allMatch(c -> isOrderIdCharacter((char) c))) {
                        return candidate;
                    }
                }
            }
            for (Object child : map.values()) {
                String found = findOrderId(child, depth + 1);
                if (found != null) return found;
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                String found = findOrderId(child, depth + 1);
                if (found != null) return found;
            }
        } else if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    String nested = findOrderId(objectMapper.readValue(trimmed, Object.class), depth + 1);
                    if (nested != null) return nested;
                } catch (Exception ignored) {
                    // Not nested JSON.
                }
            }
            if (text.contains("orderId=") || text.contains("?id=") || text.contains("&id=")) {
                return extractOrderIdFromUrl(text);
            }
        }
        return null;
    }
    
    /**
     * 同步包消息参数
     */
    @Data
    public static class SyncMessageParams {
        private List<Object> dataList;
        private int messageCount;
    }
}
