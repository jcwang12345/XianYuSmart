package com.xianyusmart.event.chatMessageEvent.lister;

import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.BuyerProfileService;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.entity.XianyuOperationLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 买家最近互动资料监听器
 */
@Component
@Slf4j
public class ChatMessageBuyerProfileListener {

    private final BuyerProfileService buyerProfileService;
    private final AccountService accountService;
    private final OperationLogService operationLogService;

    public ChatMessageBuyerProfileListener(BuyerProfileService buyerProfileService,
                                           AccountService accountService,
                                           OperationLogService operationLogService) {
        this.buyerProfileService = buyerProfileService;
        this.accountService = accountService;
        this.operationLogService = operationLogService;
    }

    @Order(1)
    @Async
    @EventListener
    public void handleChatMessageReceived(ChatMessageReceivedEvent event) {
        ChatMessageData message = event.getMessageData();
        try {
            String ownUserId = accountService.getXianyuUserId(message.getXianyuAccountId());
            if (message.getSenderUserId() != null && !message.getSenderUserId().equals(ownUserId)) {
                buyerProfileService.touchFromMessage(message.getXianyuAccountId(), message.getSenderUserId(),
                        message.getSenderUserName(), message.getMessageTime());
            }
        } catch (RuntimeException failure) {
            String failureType = failure.getClass().getSimpleName();
            log.error("Buyer message projection failed: accountId={}, requestId={}, failureType={}",
                    message.getXianyuAccountId(), message.getPnmId(), failureType);
            XianyuOperationLog audit = new XianyuOperationLog();
            audit.setXianyuAccountId(message.getXianyuAccountId());
            audit.setOperationType("BUYER_MESSAGE_PROJECTION");
            audit.setOperationModule("BUYER_PROFILE");
            audit.setOperationDesc("买家最近互动投影失败，消息主链状态不变");
            audit.setOperationStatus(0);
            audit.setTargetType("MESSAGE");
            audit.setTargetId(message.getPnmId());
            audit.setRequestId(message.getPnmId());
            audit.setOutcomeState("FAILED");
            audit.setDataSource("SYSTEM_DERIVED");
            audit.setErrorMessage(failureType);
            try {
                operationLogService.logRequired(audit);
            } catch (RuntimeException auditFailure) {
                log.error("Buyer projection failure audit could not be saved: accountId={}, requestId={}, failureType={}",
                        message.getXianyuAccountId(), message.getPnmId(), auditFailure.getClass().getSimpleName());
            }
        }
    }
}
