package com.xianyusmart.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 环境级真实平台写门禁。qa profile 默认关闭，QA_LOCAL 持久化适配器不经过本门禁。
 */
@Service
public class PlatformWritePolicy {
    private static final Set<String> WRITE_APIS = Set.of(
            "mtop.idle.pc.idleitem.publish",
            "mtop.idle.pc.idleitem.edit",
            "mtop.taobao.idle.item.downshelf",
            "com.taobao.idle.item.delete",
            "mtop.taobao.idle.item.polish",
            "mtop.taobao.idle.merchant.rate.create",
            "mtop.idle.groupon.activity.seller.freeshipping",
            "mtop.taobao.idle.logistics.merchant.consign.dummy",
            "mtop.taobao.idle.logistic.consign.dummy"
    );

    private final boolean enabled;

    public PlatformWritePolicy(@Value("${app.platform-writes.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean blocksApi(String apiName) {
        return !enabled && WRITE_APIS.contains(apiName);
    }
}
