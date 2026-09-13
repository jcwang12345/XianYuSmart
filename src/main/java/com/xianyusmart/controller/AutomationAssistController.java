package com.xianyusmart.controller;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.ReplyPreference;
import com.xianyusmart.entity.XianyuGoodsSku;
import com.xianyusmart.service.*;
import com.xianyusmart.service.reply.ReplyEnhancementService;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsSkuMapper;
import org.springframework.web.bind.annotation.*;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/automation-assist")
public class AutomationAssistController {
    private final ReplyEnhancementService replies;
    private final OrderOperationsService orders;
    private final OrderConfirmationService confirmations;
    private final GoodsSkuService skus;
    private final ItemDetailSyncService sync;
    private final XianyuAccountMapper accounts;
    private final XianyuGoodsSkuMapper skuMapper;
    public AutomationAssistController(ReplyEnhancementService replies,OrderOperationsService orders,OrderConfirmationService confirmations,
            GoodsSkuService skus,ItemDetailSyncService sync,XianyuAccountMapper accounts,XianyuGoodsSkuMapper skuMapper) {
        this.replies=replies;this.orders=orders;this.confirmations=confirmations;this.skus=skus;this.sync=sync;this.accounts=accounts;this.skuMapper=skuMapper;
    }
    private void requireAccount(Long account) { if(com.xianyusmart.context.TenantContext.get()==null || account==null || accounts.selectById(account)==null) throw new IllegalArgumentException("账号不存在或无权访问"); }
    @GetMapping("/reply-preference")
    public ResultObject<ReplyPreference> preference(@RequestParam Long accountId,@RequestParam String goodsId) {return ResultObject.success(replies.get(accountId,goodsId));}
    @PostMapping("/reply-preference")
    public ResultObject<String> save(@RequestBody ReplyPreference p) {replies.save(p);return ResultObject.success("已保存");}
    @GetMapping("/timeline")
    public ResultObject<Map<String,Object>> timeline(@RequestParam Long accountId,@RequestParam String orderId) {requireAccount(accountId);return ResultObject.success(orders.timeline(accountId,orderId));}
    @PostMapping("/confirm-retry")
    public ResultObject<String> retry(@RequestBody OrderTarget target) {requireAccount(target.accountId());confirmations.retry(target.accountId(),target.orderId());return ResultObject.success("确认任务已入队，内容不会重新发货");}
    @GetMapping("/skus")
    public ResultObject<List<XianyuGoodsSku>> skus(@RequestParam Long accountId,@RequestParam String goodsId) {requireAccount(accountId);return ResultObject.success(skus.listByXyGoodsId(goodsId,accountId));}
    @PostMapping("/skus/sync")
    public ResultObject<List<XianyuGoodsSku>> sync(@RequestBody GoodsTarget target) {
        requireAccount(target.accountId());
        if(!sync.syncSingleItem(target.accountId(),target.goodsId()))return ResultObject.failed("规格同步失败，请检查账号凭证、平台验证状态和商品状态");
        return ResultObject.success(skus.listByXyGoodsId(target.goodsId(),target.accountId()));
    }
    @PostMapping("/skus/name")
    public ResultObject<String> name(@RequestBody SkuName n) {
        requireAccount(n.accountId());
        if(n.name()==null || n.name().length()>100)throw new IllegalArgumentException("规格别名最多100字");
        if(skus.findByXyGoodsIdAndSkuId(n.goodsId(),n.accountId(),n.skuId())==null)throw new IllegalArgumentException("规格不存在或不属于此商品");
        skuMapper.update(null,new LambdaUpdateWrapper<XianyuGoodsSku>().eq(XianyuGoodsSku::getXianyuAccountId,n.accountId())
                .eq(XianyuGoodsSku::getXyGoodsId,n.goodsId()).eq(XianyuGoodsSku::getSkuId,n.skuId()).set(XianyuGoodsSku::getDisplayName,n.name()));
        return ResultObject.success("已保存");
    }
    public record OrderTarget(Long accountId,String orderId){}
    public record GoodsTarget(Long accountId,String goodsId){}
    public record SkuName(Long accountId,String goodsId,String skuId,String name){}
}
