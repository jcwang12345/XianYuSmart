package com.xianyusmart.service.impl;

import com.xianyusmart.context.*;
import com.xianyusmart.entity.*;
import com.xianyusmart.enums.*;
import com.xianyusmart.mapper.*;
import com.xianyusmart.service.AccountAutomationPolicy;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryTaskServiceProjectionTest {
    final XianyuGoodsOrderMapper orders=mock(XianyuGoodsOrderMapper.class);
    final XianyuAccountMapper accounts=mock(XianyuAccountMapper.class);
    final XianyuGoodsInfoMapper goods=mock(XianyuGoodsInfoMapper.class);
    final XianyuGoodsConfigMapper configs=mock(XianyuGoodsConfigMapper.class);
    final AccountAutomationPolicy policy=mock(AccountAutomationPolicy.class);
    final DeliveryTaskServiceImpl service=new DeliveryTaskServiceImpl(orders);
    XianyuAccount account; XianyuGoodsInfo item; XianyuGoodsConfig config;
    final Map<String,XianyuGoodsOrder> persisted=new HashMap<>();
    @BeforeEach void setup() {
        TenantContext.set(7L);AccountScopeContext.set(false,Set.of(11L));
        account=new XianyuAccount();account.setId(11L);account.setTenantId(7L);
        item=new XianyuGoodsInfo();item.setId(22L);item.setXianyuAccountId(11L);item.setXyGoodId("synthetic-item");
        config=new XianyuGoodsConfig();config.setXianyuAutoDeliveryOn(0);
        when(accounts.selectById(11L)).thenReturn(account);when(goods.selectById(22L)).thenReturn(item);
        when(configs.selectByAccountAndGoodsId(11L,"synthetic-item")).thenReturn(config);
        when(orders.selectByAccountIdAndOrderId(anyLong(),anyString())).thenAnswer(c->persisted.get(c.getArgument(1)));
        when(orders.selectById(anyLong())).thenAnswer(c->persisted.values().stream().findFirst().orElse(null));
        when(orders.insert(any())).thenAnswer(c->{XianyuGoodsOrder order=c.getArgument(0);order.setId(33L);persisted.putIfAbsent(order.getOrderId(),order);return 1;});
        ReflectionTestUtils.setField(service,"accountMapper",accounts);ReflectionTestUtils.setField(service,"goodsInfoMapper",goods);
        ReflectionTestUtils.setField(service,"goodsConfigMapper",configs);ReflectionTestUtils.setField(service,"accountAutomationPolicy",policy);
    }
    @AfterEach void cleanup(){TenantContext.clear();AccountScopeContext.clear();UserContext.clear();}
    XianyuGoodsOrder payment(String pnm){var order=new XianyuGoodsOrder();order.setXianyuAccountId(11L);order.setXianyuGoodsId(22L);order.setXyGoodsId("synthetic-item");order.setOrderId("synthetic-order");order.setPnmId(pnm);return order;}
    @Test void disabledTwoPaymentMessagesConvergeWithoutExecutionOrInventedDetails(){
        var first=service.recordPaymentProjection(payment("payment-1"),DeliveryChannel.WEBSOCKET);
        var second=service.recordPaymentProjection(payment("payment-2"),DeliveryChannel.WEBSOCKET);
        assertSame(first,second);assertEquals("REVIEW_REQUIRED",first.getDeliveryStatus());assertEquals(-1,first.getState());
        assertEquals(0,first.getBuyNum());assertEquals(0,first.getExpectedQuantity());assertNull(first.getTotalPrice());
        assertNull(first.getPaySuccessTime());assertNull(first.getSkuId());assertNull(first.getContent());assertEquals(0,first.getConfirmState());
        verify(orders,times(1)).insert(any());verify(policy,never()).guarded(any(),any(),any());
        verify(orders,never()).lockDueTasks(anyInt());verify(orders,never()).claimTasks(any(),any(),anyInt());
    }
    @Test void lifecycleProtectionRecordsReviewAndDoesNotAuthorizeDiscovery(){
        config.setXianyuAutoDeliveryOn(1);
        var order=service.recordPaymentProjection(payment("payment"),DeliveryChannel.WEBSOCKET);
        assertEquals("REVIEW_REQUIRED",order.getDeliveryStatus());verify(policy).protectTenantExecutionClaims();
        verify(policy,never()).guarded(any(),any(),any());
    }
    @Test void blacklistReasonRecordsReviewEvenWhenAutomationAllowed(){
        config.setXianyuAutoDeliveryOn(1);var request=payment("payment");request.setFailReason("synthetic automation block");
        var result=service.recordPaymentProjection(request,DeliveryChannel.WEBSOCKET);
        assertEquals("REVIEW_REQUIRED",result.getDeliveryStatus());assertEquals("synthetic automation block",result.getFailReason());
    }
    @Test void missingConfigurationRecordsReview(){
        when(configs.selectByAccountAndGoodsId(11L,"synthetic-item")).thenReturn(null);
        assertEquals("REVIEW_REQUIRED",service.recordPaymentProjection(payment("payment"),DeliveryChannel.WEBSOCKET).getDeliveryStatus());
    }
    @Test void laterEnablingCannotUpgradePreviouslyProtectedOrder(){
        var original=service.recordPaymentProjection(payment("payment-1"),DeliveryChannel.WEBSOCKET);config.setXianyuAutoDeliveryOn(1);
        var replay=service.recordPaymentProjection(payment("payment-2"),DeliveryChannel.WEBSOCKET);
        assertSame(original,replay);assertEquals("REVIEW_REQUIRED",replay.getDeliveryStatus());verify(orders,times(1)).insert(any());
    }
    @Test void completedAndRefundProtectionRemainUnchangedOnReplay(){
        for(String status:List.of("COMPLETED","REVIEW_REQUIRED")){
            var existing=payment("old");existing.setTenantId(7L);existing.setId(44L);existing.setState(1);existing.setDeliveryStatus(status);existing.setFailReason("synthetic protected refund/completion");persisted.put(existing.getOrderId(),existing);
            assertSame(existing,service.recordPaymentProjection(payment("new"),DeliveryChannel.WEBSOCKET));
            assertEquals(status,existing.getDeliveryStatus());assertEquals(1,existing.getState());
        }verify(orders,never()).insert(any());
    }
    @Test void enabledTrustedDiscoveryStillUsesExistingGuard(){
        config.setXianyuAutoDeliveryOn(1);when(policy.allows(anyLong(),any())).thenReturn(true);
        when(policy.guarded(anyLong(),any(),any())).thenAnswer(c->((Supplier<?>)c.getArgument(2)).get());
        var request=payment("payment");request.setBuyNum(2);
        var result=service.recordPaymentProjection(request,DeliveryChannel.WEBSOCKET);
        assertEquals("PENDING",result.getDeliveryStatus());assertEquals(2,result.getExpectedQuantity());
        verify(policy).guarded(eq(11L),eq(AccountAutomationPolicy.Action.DISCOVERY),any());
    }
    @Test void missingOrWrongTenantAccountScopeAndGoodsAreRejected(){
        TenantContext.clear();assertThrows(IllegalArgumentException.class,()->service.recordPaymentProjection(payment("p"),DeliveryChannel.WEBSOCKET));
        TenantContext.set(8L);assertThrows(IllegalArgumentException.class,()->service.recordPaymentProjection(payment("p"),DeliveryChannel.WEBSOCKET));
        TenantContext.set(7L);AccountScopeContext.set(false,Set.of());assertThrows(IllegalArgumentException.class,()->service.recordPaymentProjection(payment("p"),DeliveryChannel.WEBSOCKET));
        AccountScopeContext.set(false,Set.of(11L));item.setXianyuAccountId(99L);assertThrows(IllegalArgumentException.class,()->service.recordPaymentProjection(payment("p"),DeliveryChannel.WEBSOCKET));
        verify(orders,never()).insert(any());
    }
    @Test void invalidOrderAndConflictingIdempotentReferenceAreRejected(){
        var request=payment("p");request.setOrderId(null);assertThrows(IllegalArgumentException.class,()->service.recordPaymentProjection(request,DeliveryChannel.WEBSOCKET));
        var existing=payment("old");existing.setTenantId(7L);existing.setXyGoodsId("other-item");persisted.put(existing.getOrderId(),existing);
        assertThrows(IllegalStateException.class,()->service.recordPaymentProjection(payment("new"),DeliveryChannel.WEBSOCKET));verify(orders,never()).insert(any());
    }
    @Test void projectionEntryRetainsTransactionalContract() throws Exception {
        assertNotNull(DeliveryTaskServiceImpl.class.getMethod("recordPaymentProjection",XianyuGoodsOrder.class,DeliveryChannel.class).getAnnotation(Transactional.class));
    }
}
