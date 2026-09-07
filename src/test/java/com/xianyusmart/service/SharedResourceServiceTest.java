package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.FixedDeliveryTemplateReqDTO;
import com.xianyusmart.entity.MerchantResource;
import com.xianyusmart.entity.MerchantTask;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.mapper.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharedResourceServiceTest {

    @Mock MerchantResourceMapper resourceMapper;
    @Mock MerchantTaskMapper taskMapper;
    @Mock MerchantDistributionMapper distributionMapper;
    @Mock XianyuAccountMapper accountMapper;
    @Mock XianyuKamiConfigMapper kamiConfigMapper;
    @Mock MerchantShortLinkMapper shortLinkMapper;
    @Mock SharedAccountLinkMapper sharedAccountLinkMapper;
    @Mock XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;
    @Mock XianyuGoodsOrderMapper goodsOrderMapper;
    @Mock ItemService itemService;
    @Mock OrderService orderService;
    @Mock RiskControlService riskControlService;
    @Mock PlatformPublishService platformPublishService;
    @Mock OpportunityAnalysisService opportunityAnalysisService;
    @Mock WorkflowDefinitionService workflowDefinitionService;
    @Mock OperationLogService operationLogService;
    @Mock AIService aiService;
    @Mock OpportunityImageService opportunityImageService;

    @InjectMocks MerchantOperationsService merchantOperationsService;

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void batchPublishCreatesOneIndependentTaskPerAssociatedAccount() {
        UserContext.set(7L, "tester");
        XianyuAccount first = account(11L, 7L);
        XianyuAccount second = account(12L, 7L);
        when(accountMapper.selectById(11L)).thenReturn(first);
        when(accountMapper.selectById(12L)).thenReturn(second);

        MerchantResource material = new MerchantResource();
        material.setId(31L);
        material.setTenantId(7L);
        material.setResourceType("MATERIAL");
        material.setXianyuAccountId(11L);
        when(resourceMapper.selectById(31L)).thenReturn(material);
        when(sharedAccountLinkMapper.selectResourceAccounts(31L)).thenReturn(List.of(11L, 12L));
        doAnswer(invocation -> {
            MerchantTask task = invocation.getArgument(0);
            task.setId(task.getXianyuAccountId() + 100L);
            return 1;
        }).when(taskMapper).insert(any(MerchantTask.class));

        List<MerchantTask> tasks = merchantOperationsService.batchPublish(Map.of("resourceIds", List.of(31L)));

        assertEquals(List.of(11L, 12L), tasks.stream().map(MerchantTask::getXianyuAccountId).toList());
        verify(taskMapper, times(2)).insert(any(MerchantTask.class));
    }

    private static XianyuAccount account(Long id, Long tenantId) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setTenantId(tenantId);
        return account;
    }
}
