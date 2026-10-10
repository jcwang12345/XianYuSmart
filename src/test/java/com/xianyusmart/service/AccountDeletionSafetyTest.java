package com.xianyusmart.service;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuOperationLogMapper;
import com.xianyusmart.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeletionSafetyTest {
    @Test void legacyDeleteCannotBypassVersionOwnerPreflightOrEraseHistory(){
        var service=new AccountServiceImpl();var accounts=mock(XianyuAccountMapper.class);
        var orders=mock(XianyuGoodsOrderMapper.class);var audits=mock(XianyuOperationLogMapper.class);
        ReflectionTestUtils.setField(service,"accountMapper",accounts);ReflectionTestUtils.setField(service,"orderMapper",orders);ReflectionTestUtils.setField(service,"operationLogMapper",audits);
        var failure=assertThrows(BusinessException.class,()->service.deleteAccountAndRelatedData(900001L));
        assertEquals(400,failure.getCode());verifyNoInteractions(accounts,orders,audits);
    }
}
