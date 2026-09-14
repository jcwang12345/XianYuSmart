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
                    "商品主档没有 SKU 数量证据，请重新同步商品详情");
        }
        if (declared > 0 && declared == verified) {
            return new Readiness(declared, verified, Status.FULL,
                    "主档声明数量与已同步 SKU 子项一致");
        }
        if (declared == 0 && verified == 0 && "FULL".equalsIgnoreCase(goods.getCoverageStatus())) {
            return new Readiness(0, 0, Status.EMPTY_VERIFIED,
                    "平台完整快照确认商品没有可拆分 SKU");
        }
        if (declared > 0 && verified == 0) {
            return new Readiness(declared, 0, Status.UNSYNCED,
                    "主档声明 " + declared + " 个 SKU，但子项尚未同步");
        }
        return new Readiness(declared, verified, Status.PARTIAL,
                "主档声明 " + declared + " 个 SKU，已验证 " + verified + " 个");
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
}
