package com.xianyusmart.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 单一总闸：关闭后，后台定时任务不得主动访问平台或改写平台凭据。 */
@Component
public class BackgroundPlatformActivityPolicy {

    @Value("${app.background-platform-activity-enabled:true}")
    private boolean enabled = true;

    public boolean enabled() {
        return enabled;
    }
}
