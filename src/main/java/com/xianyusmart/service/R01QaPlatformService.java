package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.exception.DeliveryUncertainException;
import com.xianyusmart.service.delivery.OrderDetailFetcher;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Only external platform facts/results are simulated. Business state is written by real services. */
@Service
public class R01QaPlatformService {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final long tenant;
    private final Set<Long> accounts;
    private final Map<String, Fact> facts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> connections = new ConcurrentHashMap<>();
    public R01QaPlatformService(Environment environment, JdbcTemplate jdbc,
            @Value("${app.r01-qa.enabled:false}") boolean enabled,
            @Value("${app.r01-qa.tenant-id:-1}") long tenant,
            @Value("${app.r01-qa.account-ids:}") String accounts) {
        this.environment=environment; this.jdbc=jdbc; this.enabled=enabled; this.tenant=tenant;
        this.accounts=Arrays.stream(accounts.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }
    @PostConstruct
    public void validate() {
        if (enabled && (!Arrays.asList(environment.getActiveProfiles()).contains("qa") || tenant<=0 || accounts.isEmpty()))
            throw new IllegalStateException("R01 QA requires qa profile and explicit tenant/account allowlist");
    }
    public boolean enabled() { return enabled; }
    public void require(Long account, String object) {
        if (!enabled) throw new BusinessException(404,"R01 QA 未启用");
        if (!Objects.equals(TenantContext.get(),tenant) || !accounts.contains(account))
            throw new BusinessException(403,"R01 QA 租户/账号不在白名单");
        if (object==null || !object.matches("QA-R01-[A-Za-z0-9._:-]{1,70}"))
            throw new BusinessException(403,"R01 QA 对象前缀不合法");
        Long safe=jdbc.queryForObject("""
            SELECT COUNT(*) FROM xianyu_account a WHERE a.id=? AND a.tenant_id=?
              AND a.unb LIKE 'QA-%'
              AND NOT EXISTS(SELECT 1 FROM xianyu_cookie c WHERE c.xianyu_account_id=a.id)
            """,Long.class,account,tenant);
        if (!Long.valueOf(1).equals(safe)) throw new BusinessException(403,"R01 QA 账号含凭据或非隔离账号");
    }
    public boolean handles(Long account) { return enabled && accounts.contains(account); }
    public boolean handles(Long account, String object) {
        return handles(account) || object != null && object.startsWith("QA-R01-");
    }
    public boolean connected(Long account) { require(account,"QA-R01-connection"); return connections.getOrDefault(account,true); }
    public void connection(Long account, boolean value) { require(account,"QA-R01-connection"); connections.put(account,value); }
    public List<com.xianyusmart.entity.XianyuAccount> discoveryAccounts() {
        List<com.xianyusmart.entity.XianyuAccount> result=new ArrayList<>();
        Long previous=TenantContext.get();
        try {
            TenantContext.set(tenant);
            for(Long id:accounts) {
                require(id,"QA-R01-discovery");
                Integer status=jdbc.queryForObject("SELECT status FROM xianyu_account WHERE tenant_id=? AND id=?",Integer.class,tenant,id);
                if(Integer.valueOf(1).equals(status)) {
                    var a=new com.xianyusmart.entity.XianyuAccount(); a.setId(id); a.setTenantId(tenant); a.setStatus(status); result.add(a);
                }
            }
        } finally { if(previous==null) TenantContext.clear(); else TenantContext.set(previous); }
        return result;
    }
    public Fact register(Long account, String key, String status, String scenario, String goods, String buyer) {
        require(account,key); require(account,goods); require(account,buyer);
        if (!Set.of("PAID","UNPAID","CLOSED","REFUNDING","REFUNDED","SHIPPED").contains(status)
                || !Set.of("SUCCESS","UNKNOWN","RISK_BLOCK","BUYER_MISMATCH","GOODS_MISMATCH","ORDER_MISMATCH","SKU_AMBIGUOUS").contains(scenario))
            throw new BusinessException(400,"R01 QA 场景不支持");
        Fact fact=new Fact(account,key,status,scenario,goods,buyer);
        Fact previous=facts.putIfAbsent(key,fact);
        if (previous!=null && !previous.equals(fact)) throw new BusinessException(409,"同一QA键不能替换平台事实");
        return previous==null ? fact : previous;
    }
    public Fact fact(Long account,String key) {
        require(account,key); Fact fact=facts.get(key);
        if (fact==null || !fact.account().equals(account)) throw new BusinessException(404,"QA 平台订单未登记");
        return fact;
    }
    private void count(String key,String method) { calls.computeIfAbsent(key+"|"+method,k->new AtomicInteger()).incrementAndGet(); }
    public Map<String,Integer> counts(String key) {
        Map<String,Integer> result=new TreeMap<>();
        calls.forEach((k,v)-> { if(k.startsWith(key+"|")) result.put(k.substring(key.length()+1),v.get()); });
        return result;
    }
    public List<Map<String,Object>> pending(Long account) {
        require(account,"QA-R01-discovery"); count("account-"+account,"pendingRead");
        return facts.values().stream().filter(f -> f.account().equals(account)).map(f -> Map.<String,Object>of(
            "commonData",Map.of("orderId",f.key(),"itemId",f.goods(),"orderStatus",statusText(f.status()),
                "createTime","2026-10-01 00:00:00","paySuccessTime","2026-10-01 00:00:01"),
            "buyerInfoVO",Map.of("userId",f.buyer(),"userNick","QA buyer"),
            "itemVO",Map.of("title","QA R01"),"priceVO",Map.of("totalPrice","1.00","buyNum",1))).toList();
    }
    public OrderDetailFetcher.OrderDetailInfo detail(Long account,String key) {
        Fact f=fact(account,key); count(key,"detailRead");
        var d=new OrderDetailFetcher.OrderDetailInfo();
        d.orderId=f.scenario().equals("ORDER_MISMATCH") ? "QA-R01-other-order" : key;
        d.goodsId=f.scenario().equals("GOODS_MISMATCH") ? "QA-R01-other-goods" : f.goods();
        d.buyerUserId=f.scenario().equals("BUYER_MISMATCH") ? "QA-R01-other-buyer" : f.buyer();
        d.tradeStatus=f.status(); d.buyerUserName="QA buyer"; d.buyNum=1; d.totalPrice="1.00";
        d.orderCreateTime="2026-10-01 00:00:00"; d.paySuccessTime="2026-10-01 00:00:01";
        if(f.status().equals("SHIPPED")) d.consignTime="2026-10-01 00:00:02";
        if(f.scenario().equals("SKU_AMBIGUOUS")) d.skuId="QA-R01-unverified-sku";
        return d;
    }
    public Map<String,Object> detailMap(Long account,String key) {
        var d=detail(account,key);
        Fact baseline=fact(account,key);
        // Discovery response describes the initial identity; mismatch is injected only at execution's fresh detail read.
        d.orderId=baseline.key(); d.goodsId=baseline.goods(); d.buyerUserId=baseline.buyer();
        Map<String,Object> common=new LinkedHashMap<>();
        common.put("orderId",d.orderId); common.put("orderStatus",statusText(d.tradeStatus));
        common.put("createTime",d.orderCreateTime); common.put("paySuccessTime",d.paySuccessTime); common.put("consignTime",d.consignTime);
        return Map.of("module",Map.of("merchantCommonData",common,"merchantBuyerVO",Map.of("userId",d.buyerUserId,"userNick",d.buyerUserName),
            "merchantItemVO",Map.of("itemId",d.goodsId,"title","QA R01"),"merchantPriceVO",Map.of("totalPrice","1.00","buyNum",1)));
    }
    public void requireActive(Long account) {
        require(account,"QA-R01-active");
        if(!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT status FROM xianyu_account WHERE tenant_id=? AND id=?",Integer.class,tenant,account)))
            throw new BusinessException(403,"R01 QA 账号已停用");
    }
    public String consign(Long account,String key) {
        Fact f=fact(account,key); requireActive(account);
        if(f.scenario().equals("RISK_BLOCK")) { count(key,"riskBlocked"); return OrderService.CONSIGN_DEFERRED; }
        count(key,"consign");
        if(f.scenario().equals("UNKNOWN")) return OrderService.CONSIGN_UNCERTAIN;
        return OrderService.CONSIGN_SUCCESS;
    }
    public String confirm(Long account,String key) { fact(account,key); requireActive(account); count(key,"confirm"); return "{\"ret\":[\"SUCCESS::调用成功\"]}"; }
    public boolean send(Long account,String session,String recipient) {
        require(account,session.replace("@goofish","")); require(account,recipient);
        requireActive(account);
        count(session.replace("@goofish",""),"textSend");
        Fact f=facts.values().stream().filter(x->x.account().equals(account)&&x.buyer().equals(recipient)).findFirst().orElse(null);
        if(f!=null && f.scenario().equals("UNKNOWN")) throw new DeliveryUncertainException("QA simulated unknown external result");
        return true;
    }
    public static String statusText(String s) { return switch(s) { case "PAID"->"待发货"; case "UNPAID"->"待付款"; case "SHIPPED"->"待收货"; case "REFUNDING"->"退款中"; case "REFUNDED"->"已退款"; default->"已关闭"; }; }
    public record Fact(Long account,String key,String status,String scenario,String goods,String buyer) {}
}
