package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.controller.dto.KamiConfigReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuKamiConfigMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KamiConfigSharingTest {

    @Mock XianyuKamiConfigMapper configMapper;
    @Mock XianyuKamiItemMapper itemMapper;
    @Mock XianyuAccountMapper accountMapper;
    @Mock SharedAccountLinkMapper sharedAccountLinkMapper;

    @Test
    void sharedInventoryPoolIsAssociatedWithEverySelectedAccount() {
        KamiConfigServiceImpl service = new KamiConfigServiceImpl();
        ReflectionTestUtils.setField(service, "kamiConfigMapper", configMapper);
        ReflectionTestUtils.setField(service, "kamiItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "xianyuAccountMapper", accountMapper);
        ReflectionTestUtils.setField(service, "sharedAccountLinkMapper", sharedAccountLinkMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        when(accountMapper.selectById(anyLong())).thenAnswer(invocation -> {
            XianyuAccount account = new XianyuAccount();
            account.setId(invocation.getArgument(0));
            account.setTenantId(3L);
            return account;
        });
        doAnswer(invocation -> {
            XianyuKamiConfig config = invocation.getArgument(0);
            config.setId(41L);
            return 1;
        }).when(configMapper).insert(any(XianyuKamiConfig.class));
        when(itemMapper.countUnused(41L)).thenReturn(0);
        when(sharedAccountLinkMapper.selectKamiConfigAccounts(41L)).thenReturn(List.of(1L, 2L));

        KamiConfigReqDTO request = new KamiConfigReqDTO();
        request.setXianyuAccountId(1L);
        request.setXianyuAccountIds(List.of(1L, 2L));
        request.setSharingMode("SHARED");
        request.setAliasName("共享卡密");
        request.setSourceType("LOCAL");

        var result = service.createOrUpdateConfig(request);

        assertEquals(200, result.getCode());
        assertEquals("SHARED", result.getData().getSharingMode());
        assertEquals(List.of(1L, 2L), result.getData().getXianyuAccountIds());
        verify(sharedAccountLinkMapper).insertKamiConfigAccounts(41L, 3L, List.of(1L, 2L));
    }
}
