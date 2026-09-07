package com.xianyusmart.service;

import com.xianyusmart.controller.dto.FixedDeliveryTemplateReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuFixedDeliveryTemplateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FixedDeliveryTemplateSharingTest {

    @Mock XianyuFixedDeliveryTemplateMapper templateMapper;
    @Mock XianyuAccountMapper accountMapper;
    @Mock BuyerMessageService buyerMessageService;
    @Mock SharedAccountLinkMapper sharedAccountLinkMapper;

    @Test
    void savesOneTemplateAndAssociatesAllSelectedAccounts() {
        XianyuAccount first = account(1L);
        XianyuAccount second = account(2L);
        when(accountMapper.selectById(1L)).thenReturn(first);
        when(accountMapper.selectById(2L)).thenReturn(second);
        when(buyerMessageService.normalizeDeliveryMessageTemplate(any())).thenReturn("{deliveryContent}");
        when(buyerMessageService.renderVariables(any(), any(), any(), any())).thenReturn("下载地址");
        doAnswer(invocation -> {
            XianyuFixedDeliveryTemplate template = invocation.getArgument(0);
            template.setId(8L);
            return 1;
        }).when(templateMapper).insert(any(XianyuFixedDeliveryTemplate.class));

        FixedDeliveryTemplateReqDTO request = new FixedDeliveryTemplateReqDTO();
        request.setXianyuAccountIds(List.of(1L, 2L));
        request.setTemplateName("资料模板");
        request.setDeliveryContent("下载地址");
        request.setMessageTemplate("{deliveryContent}");

        var result = new FixedDeliveryTemplateService(templateMapper, accountMapper,
                buyerMessageService, sharedAccountLinkMapper).save(request);

        assertEquals(200, result.getCode());
        assertEquals(List.of(1L, 2L), result.getData().getXianyuAccountIds());
        verify(sharedAccountLinkMapper).insertFixedTemplateAccounts(8L, 9L, List.of(1L, 2L));
    }

    private static XianyuAccount account(Long id) {
        XianyuAccount account = new XianyuAccount();
        account.setId(id);
        account.setTenantId(9L);
        return account;
    }
}
