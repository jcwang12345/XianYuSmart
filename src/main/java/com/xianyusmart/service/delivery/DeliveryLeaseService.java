package com.xianyusmart.service.delivery;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuGoodsOrder;
import com.xianyusmart.mapper.DeliveryExecutionMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;

@Service
public class DeliveryLeaseService {
    private final DeliveryExecutionMapper mapper;
    private final TaskScheduler scheduler;
    public DeliveryLeaseService(DeliveryExecutionMapper mapper, @Qualifier("deliveryLeaseScheduler") TaskScheduler scheduler) {
        this.mapper = mapper;
        this.scheduler = scheduler;
    }
    public void run(XianyuGoodsOrder task, Runnable action) {
        String token = task.getLeaseOwner();
        if (token == null) {
            token = UUID.randomUUID().toString();
            if (mapper.claimManual(task.getId(), token) != 1) throw new IllegalStateException("订单正在处理、已经完成或需要人工核对");
        }
        final String claim = token;
        Long tenant = task.getTenantId();
        var renewal = scheduler.scheduleAtFixedRate(() -> {
            try { TenantContext.set(tenant); mapper.renew(task.getId(), claim); }
            finally { TenantContext.clear(); }
        }, Duration.ofSeconds(30));
        try (var scope = new DeliveryExecution(claim,
                () -> mapper.active(task.getId(), claim) == 1,
                () -> mapper.begin(task.getId(), claim) == 1)) {
            DeliveryExecution.check();
            action.run();
        } finally {
            if (renewal != null) renewal.cancel(false);
        }
    }
}
