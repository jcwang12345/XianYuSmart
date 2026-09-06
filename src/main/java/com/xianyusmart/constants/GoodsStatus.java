package com.xianyusmart.constants;

import java.util.Set;

/**
 * 闲鱼商品状态。
 *
 * <p>状态值来自闲鱼商品列表接口；未知值必须原样保留并在前端兜底展示，
 * 不能因为平台新增状态而把商品从系统中隐藏。</p>
 */
public enum GoodsStatus {
    PLATFORM_OFF_SHELF(-98, "平台下架"),
    REVIEWING(-9, "审核中"),
    DELETED(-1, "已删除"),
    ON_SALE(0, "在售"),
    OFF_SHELF(1, "已下架"),
    SOLD(2, "已售出");

    private static final Set<Integer> REMOTE_ACTIVE_CODES = Set.of(ON_SALE.code, REVIEWING.code);

    private final int code;
    private final String description;

    GoodsStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static boolean isOnSale(Integer code) {
        return code != null && code == ON_SALE.code;
    }

    /**
     * “在售”远程分组会返回正常在售和审核中的商品。
     */
    public static Set<Integer> remoteActiveCodes() {
        return REMOTE_ACTIVE_CODES;
    }
}
