package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.entity.XianyuGoodsInfo;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.mapper.XianyuGoodsInfoMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * V5-PRD-03: one source of truth for SKU synchronization readiness.
 * Configuration and delivery execution must use the same decision so a partial
 * snapshot can never be treated as a single-SKU product.
 */
@Service
public class GoodsSkuReadinessService {

    public enum Status { FULL, EMPTY_VERIFIED, UNSYNCED, PARTIAL }

    public record Readiness(Integer declaredCount, int verifiedCount, Status status, String message) {
        public boolean complete() {
            return status == Status.FULL || status == Status.EMPTY_VERIFIED;
        }

        public boolean hasSkuChildren() {
            return status == Status.FULL && verifiedCount > 0;
        }
    }

    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;

    @Autowired
    private GoodsSkuService goodsSkuService;

    @Autowired
    private com.xianyusmart.mapper.XianyuGoodsAutoDeliveryConfigMapper deliveryConfigMapper;
    @Autowired
    private FixedDeliveryTemplateService fixedDeliveryTemplateService;

    @Autowired
    private UniformCardDeliveryPolicyService uniformCardDeliveryPolicyService;

    public boolean permitsUniformCard(Long accountId, String goodsId,
            com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig config) {
        return uniformCardDeliveryPolicyService != null
                && uniformCardDeliveryPolicyService.permits(accountId, goodsId, config);
    }

    public boolean permitsLimitedFixedTemplate(Long accountId, String goodsId, Readiness readiness,
            com.xianyusmart.entity.XianyuGoodsAutoDeliveryConfig config) {
        return Integer.valueOf(0).equals(readiness.declaredCount()) && readiness.verifiedCount() == 0
                && config != null && (config.getSkuId() == null || config.getSkuId().isBlank())
                && Integer.valueOf(1).equals(config.getDeliveryMode()) && config.getFixedTemplateId() != null
                && deliveryConfigMapper.countSkuSpecificByAccountAndGoodsId(accountId, goodsId) == 0
                && fixedDeliveryTemplateService.findOwnedTemplate(accountId, config.getFixedTemplateId()) != null;
    }

    public Readiness inspect(Long accountId, String goodsId) {
        LambdaQueryWrapper<XianyuGoodsInfo> query = new LambdaQueryWrapper<>();
        query.eq(XianyuGoodsInfo::getXianyuAccountId, accountId)
                .eq(XianyuGoodsInfo::getXyGoodId, goodsId);
        XianyuGoodsInfo goods = goodsInfoMapper.selectOne(query);
        if (goods == null) {
            throw new IllegalArgumentException("商品不存在或不属于当前账号");
        }

        Integer declared = goods.getSkuCount();
        int verified = goodsSkuService.countByXyGoodsId(goodsId, accountId);
        if (declared == null) {
            return new Readiness(null, verified, Status.UNSYNCED,
                    "商品规格/版本/套餐信息未确认；可先保存配置，开启前需完成商品详情验证");
        }
        if (declared > 0 && declared == verified) {
            return new Readiness(declared, verified, Status.FULL,
                    "商品规格数量与已同步版本/套餐一致");
        }
        if (declared == 0 && verified == 0 && "FULL".equalsIgnoreCase(goods.getCoverageStatus())) {
            return new Readiness(0, 0, Status.EMPTY_VERIFIED,
                    "完整可信快照确认这是无规格商品");
        }
        if (declared > 0 && verified == 0) {
            return new Readiness(declared, 0, Status.UNSYNCED,
                    "商品声明 " + declared + " 个规格，已验证 0 个；可先保存配置，开启前需完成验证");
        }
        return new Readiness(declared, verified, Status.PARTIAL,
                declared == 0 && verified == 0
                        ? "商品规格/版本/套餐信息未确认；0/0不代表无规格，可先保存配置，开启前需完成验证"
                        : "商品声明 " + declared + " 个规格，已验证 " + verified + " 个；开启前需完成验证");
    }

    public Readiness requireComplete(Long accountId, String goodsId) {
        Readiness readiness = inspect(accountId, goodsId);
        if (!readiness.complete()) {
            throw new IllegalStateException(readiness.message() + "；为避免错发，已停止自动发货");
        }
        return readiness;
    }

    public XianyuGoodsSku requireValidSelection(Long accountId, String goodsId, String skuId) {
        Readiness readiness = requireComplete(accountId, goodsId);
        if (!readiness.hasSkuChildren()) {
            if (skuId != null && !skuId.isBlank()) {
                throw new IllegalArgumentException("商品完整快照确认没有可拆分规格，不能绑定 SKU");
            }
            return null;
        }
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("该商品有 " + readiness.verifiedCount() + " 个规格，请先选择商品规格");
        }
        XianyuGoodsSku sku = goodsSkuService.findByXyGoodsIdAndSkuId(goodsId, accountId, skuId.trim());
        if (sku == null) {
            throw new IllegalArgumentException("商品规格不存在、已过期或不属于当前账号商品");
        }
        return sku;
    }

    /** Validate a draft mapping without treating incomplete data as execution readiness. */
    public XianyuGoodsSku requireConfigSelection(Long accountId, String goodsId, String skuId) {
        Readiness readiness = inspect(accountId, goodsId);
        if (readiness.complete()) return requireValidSelection(accountId, goodsId, skuId);
        if (skuId == null || skuId.isBlank()) return null;
        XianyuGoodsSku sku = goodsSkuService.findByXyGoodsIdAndSkuId(goodsId, accountId, skuId.trim());
        if (sku == null) throw new IllegalArgumentException("商品规格不存在、已过期或不属于当前账号商品");
        return sku;
    }
}
