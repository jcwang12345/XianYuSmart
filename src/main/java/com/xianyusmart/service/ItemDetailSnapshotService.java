package com.xianyusmart.service;

import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.entity.XianyuGoodsSkuProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 将一次已验证的平台详情响应作为单个本地快照写入。
 * 任一详情、SKU、属性或计数写入失败时，外层事务整体回滚。
 */
@Service
public class ItemDetailSnapshotService {

    private final GoodsInfoService goodsInfoService;
    private final GoodsSkuService goodsSkuService;
    private final GoodsSkuPropertyService goodsSkuPropertyService;

    public ItemDetailSnapshotService(GoodsInfoService goodsInfoService,
                                     GoodsSkuService goodsSkuService,
                                     GoodsSkuPropertyService goodsSkuPropertyService) {
        this.goodsInfoService = goodsInfoService;
        this.goodsSkuService = goodsSkuService;
        this.goodsSkuPropertyService = goodsSkuPropertyService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(Long accountId, String itemId, String detail,
                     List<XianyuGoodsSku> skus,
                     List<XianyuGoodsSkuProperty> properties) {
        if (!goodsInfoService.updateDetailInfo(accountId, itemId, detail)) {
            throw new IllegalStateException("商品详情快照写入失败");
        }
        goodsSkuService.saveSkus(itemId, accountId, skus);
        goodsSkuPropertyService.saveProperties(itemId, accountId, properties);
        if (!goodsInfoService.updateSkuCount(accountId, itemId, skus.size())) {
            throw new IllegalStateException("商品 SKU 声明数写入失败");
        }
    }
}
