package com.xianyusmart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded QA-only read faults. The real MVC permissions and read run before a response fault is applied. */
@Service
@Profile("qa")
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name="app.r03.qa.enabled",havingValue="true")
public class QaHistoricalAcceptanceService extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final AccountAccessService access;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final long tenant;
    private final Set<Long> accounts;
    private final ProductEventService productEvents;
    private final ProductEventSafety safety;
    private final SysUserMapper users;
    private final PlatformPermissionService permissions;
    private final String signingKey;
    private final Map<String,Fault> faults=new ConcurrentHashMap<>();
    private static final Set<String> OPERATIONS=Set.of("PRODUCT_LIST","PRODUCT_DETAIL","ORDER_LIST","PUBLISH_CAPABILITIES");
    private static final String HEADER="X-QA-R03-Request-Id";

    public QaHistoricalAcceptanceService(JdbcTemplate jdbc,AccountAccessService access,ObjectMapper json,PlatformTransactionManager transactionManager,Environment env,
            @Value("${app.r03.qa.tenant-id:-1}") long tenant,@Value("${app.r03.qa.account-ids:}") String ids,
            ProductEventService productEvents,ProductEventSafety safety,SysUserMapper users,PlatformPermissionService permissions,
            @Value("${app.r03.qa.signing-key:}") String signingKey) {
        if(!Arrays.asList(env.getActiveProfiles()).contains("qa")||tenant<1)throw new IllegalStateException("R03 QA requires qa profile and tenant");
        this.jdbc=jdbc;this.access=access;this.json=json;this.transaction=new TransactionTemplate(transactionManager);this.tenant=tenant;
        var set=new HashSet<Long>();for(String id:ids.split(","))if(!id.isBlank())set.add(Long.valueOf(id.trim()));
        this.accounts=Set.copyOf(set);
        this.productEvents=productEvents;this.safety=safety;this.users=users;this.permissions=permissions;this.signingKey=signingKey;
    }
    private void objectBoundary(Long account) {
        if(account==null||!accounts.contains(account))throw new BusinessException(403,"R03 QA账号不在白名单");
        var rows=jdbc.queryForList("SELECT account_note,unb FROM xianyu_account WHERE tenant_id=? AND id=?",tenant,account);
        if(rows.size()!=1||!String.valueOf(rows.getFirst().get("account_note")).startsWith("QA-R03-")
                ||!String.valueOf(rows.getFirst().get("unb")).startsWith("QA-R03-")
                ||jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_cookie WHERE xianyu_account_id=?",Integer.class,account)!=0)
            throw new BusinessException(403,"R03 QA对象保护失败，禁止回落平台");
    }
    private void authority(Long account) {
        if(!Objects.equals(TenantContext.get(),tenant)||UserContext.getUserId()==null)throw new BusinessException(403,"R03 QA主体或操作者不匹配");
        objectBoundary(account);access.requireAccess(account);
        ProductEventService.requireManager(users,permissions,access,account,PermissionCatalog.ACTION_SYSTEM_WRITE);
    }

    public Map<String,Object> signedEvent(Long account,String eventId,String goodsId,String occurredAt,
            Long declaredTenant,Long timestamp,String signature,Map<String,Object> before,Map<String,Object> after) {
        authority(account);
        if(declaredTenant!=null&&!Objects.equals(declaredTenant,tenant))throw new BusinessException(403,"QA事件租户不匹配");
        if(signingKey==null||signingKey.length()<32||timestamp==null||timestamp<Instant.now().toEpochMilli()-300000
                ||timestamp>Instant.now().toEpochMilli()+300000||signature==null||!signature.matches("[0-9a-fA-F]{64}"))
            throw new BusinessException(403,"QA事件签名无效或已过期");
        var payload=new LinkedHashMap<String,Object>();payload.put("version",1);payload.put("timestamp",timestamp);
        payload.put("tenantId",tenant);payload.put("accountId",account);payload.put("eventId",eventId);payload.put("goodsId",goodsId);
        payload.put("occurredAt",occurredAt);payload.put("before",before);payload.put("after",after);
        byte[] body=safety.canonical(payload).getBytes(StandardCharsets.UTF_8);
        if(body.length>64*1024)throw new BusinessException(400,"QA事件请求过大");
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature)))throw new BusinessException(403,"QA事件签名无效或已过期");
        }catch(BusinessException rejected){throw rejected;}catch(Exception error){throw new IllegalStateException("QA HMAC unavailable");}
        return productEvents.observeQaProduct(account,goodsId,eventId,occurredAt,before,after);
    }
    private String key(String value) {
        if(value==null||!value.matches("QA-R03-[A-Za-z0-9_-]{1,65}"))throw new BusinessException(400,"R03 QA请求ID无效");return value;
    }
    private String token(HttpServletRequest request) {
        String value=request.getHeader("Authorization");if(value==null||!value.startsWith("Bearer "))throw new BusinessException(403,"R03 QA需要当前受保护会话");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    public Map<String,Object> fixtures(Long account,String requestId,int count) {
        authority(account);key(requestId);if(count<1||count>1000)throw new BusinessException(400,"R03 QA商品数量须为1到1000");
        return transaction.execute(status -> {
        for(int index=0;index<count;index++) {
            String goods=requestId+"-"+index;
            long id=UUID.nameUUIDFromBytes((tenant+"|"+account+"|"+goods).getBytes(StandardCharsets.UTF_8)).getMostSignificantBits()&Long.MAX_VALUE;
            jdbc.update("""
                INSERT INTO xianyu_goods(id,tenant_id,xianyu_account_id,xy_good_id,title,cover_pic,status,sku_count,
                    detail_info,product_source,publish_channel,sync_status,coverage_status,last_synced_time,last_sync_request_id)
                VALUES(?,?,?,?,?,?,1,0,?,'MANUAL_IMPORT','QA_LOCAL','SUCCEEDED','FULL',NOW(3),?)
                ON DUPLICATE KEY UPDATE id=id
                """,id,tenant,account,goods,"QA historical product "+index,index==0?"/qa-r03-image-missing.png":null,
                    jsonText(Map.of("description","QA synthetic long text. ".repeat(300),"skuList",List.of())),requestId);
        }
        return Map.of("requestId",requestId,"accountId",account,"count",count,"goodsPrefix",requestId+"-",
                "platformCalls",0,"automaticConfigurationCreated",false,"imageFailurePath","/qa-r03-image-missing.png");
        });
    }
    public synchronized Map<String,Object> configure(Long account,String requestId,String operation,String mode,int calls,int delayMs,HttpServletRequest request) {
        authority(account);key(requestId);
        if(!OPERATIONS.contains(operation)||!Set.of("SLOW","FAIL","RESET").contains(mode)||calls<1||calls>20||delayMs<0||delayMs>3000)
            throw new BusinessException(400,"R03 QA故障范围或上限无效");
        String bearerHash=token(request);var existing=faults.get(requestId);
        if(existing!=null&&(!Objects.equals(existing.operator,UserContext.getUserId())||existing.account!=account||!existing.bearerHash.equals(bearerHash)))
            throw new BusinessException(403,"R03 QA请求属于其他会话");
        if("RESET".equals(mode)){if(existing!=null)existing.reset=true;return Map.of("requestId",requestId,"reset",true);}
        // Keep memory bounded; never retain raw credentials or request/response content.
        faults.entrySet().removeIf(entry->entry.getValue().expires<Instant.now().toEpochMilli());
        if(faults.size()>=64&&!faults.containsKey(requestId))throw new BusinessException(409,"R03 QA故障上限已满");
        faults.values().stream().filter(old->old.account==account&&old.operation.equals(operation)&&old.bearerHash.equals(bearerHash)).forEach(old->old.reset=true);
        var fault=new Fault(account,UserContext.getUserId(),bearerHash,operation,mode,calls,delayMs);faults.put(requestId,fault);
        return projection(requestId,fault);
    }
    public Map<String,Object> evidence(String requestId,HttpServletRequest request) {
        var fault=faults.get(key(requestId));if(fault==null)throw new BusinessException(404,"R03 QA请求不存在");
        if(!Objects.equals(TenantContext.get(),tenant))throw new BusinessException(403,"R03 QA主体不匹配");
        objectBoundary(fault.account);ProductEventService.requireManager(users,permissions,access,fault.account,PermissionCatalog.MENU_GOODS);
        if(!Objects.equals(fault.operator,UserContext.getUserId())||!fault.bearerHash.equals(token(request)))throw new BusinessException(403,"R03 QA会话不匹配");
        return projection(requestId,fault);
    }
    private Map<String,Object> projection(String id,Fault f) {
        return Map.of("requestId",id,"accountId",f.account,"operation",f.operation,"mode",f.mode,"remaining",Math.max(0,f.remaining.get()),
                "applied",f.applied.get(),"reset",f.reset,"expiresAt",f.expires,"platformCalls",0);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/qa/r03/") || (request.getHeader(HEADER)==null && automatic(request)==null);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        try {
            boolean implicit=request.getHeader(HEADER)==null;
            var automatic=implicit?automatic(request):null;
            if(implicit&&automatic==null){chain.doFilter(request,response);return;}
            String id=implicit?automatic.getKey():key(request.getHeader(HEADER));var fault=faults.get(id);
            if(fault==null||fault.reset||fault.expires<Instant.now().toEpochMilli())throw new BusinessException(410,"R03 QA故障未配置或已复位");
            if(!fault.bearerHash.equals(token(request)))throw new BusinessException(403,"R03 QA故障不属于当前会话");
            HttpServletRequest forwarded=request;
            if(Set.of("PRODUCT_LIST","ORDER_LIST").contains(fault.operation)) {
                byte[] body=request.getInputStream().readNBytes(32*1024+1);if(body.length>32*1024)throw new BusinessException(400,"R03 QA读取请求过大");
                JsonNode input;try{input=json.readTree(body);}catch(Exception invalid){throw new BusinessException(400,"R03 QA读取请求格式无效");}
                JsonNode ids=input==null?null:input.get("accountIds");
                forwarded=new BodyRequest(request,body);
                if(ids==null||!ids.isArray()||ids.size()!=1||ids.get(0).asLong(-1)!=fault.account) {
                    if(implicit){chain.doFilter(forwarded,response);return;}
                    throw new BusinessException(403,"R03 QA读取须绑定单个测试账号");
                }
            }
            if(!matches(forwarded,fault))throw new BusinessException(403,"R03 QA仅允许已登记的本地读取路由");
            objectBoundary(fault.account);
            var cached=new ContentCachingResponseWrapper(response);
            try {
                chain.doFilter(forwarded,cached);
                JsonNode result;try{result=json.readTree(cached.getContentAsByteArray());}catch(Exception ignored){result=null;}
                // Existing auth and business denial must stay visible; never turn a denial into QA success/failure.
                if(cached.getStatus()==200&&result!=null&&result.path("code").asInt()!=403&&result.path("code").asInt()==200
                        &&!fault.reset&&fault.remaining.getAndUpdate(value->Math.max(0,value-1))>0) {
                    if("SLOW".equals(fault.mode))try{Thread.sleep(fault.delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ServletException("R03 QA delay interrupted",interrupted);}
                    else {cached.resetBuffer();cached.setStatus(503);cached.setContentType("application/json;charset=UTF-8");cached.getWriter().write(jsonText(Map.of("code",503,"message","QA_R03_READ_FAILURE","data",Map.of("requestId",id,"retrySafe",true))));cached.getWriter().flush();}
                    cached.setHeader("X-QA-R03-Request-Id",id);
                    fault.applied.incrementAndGet();
                }
            }finally{cached.copyBodyToResponse();}
        }catch(BusinessException rejected){response.setStatus(rejected.getCode());response.setContentType("application/json;charset=UTF-8");response.getWriter().write(jsonText(Map.of("code",rejected.getCode(),"message",rejected.getMessage())));}
    }
    private Map.Entry<String,Fault> automatic(HttpServletRequest request) {
        if(request.getHeader("Authorization")==null)return null;
        String hash;try{hash=token(request);}catch(BusinessException denied){return null;}
        return faults.entrySet().stream().filter(entry->{var f=entry.getValue();return !f.reset&&f.remaining.get()>0&&f.expires>Instant.now().toEpochMilli()&&f.bearerHash.equals(hash)&&matches(request,f);}).findFirst().orElse(null);
    }
    private boolean matches(HttpServletRequest r,Fault f) {
        String uri=r.getRequestURI(),method=r.getMethod();
        return switch(f.operation){
            case "PRODUCT_LIST"->"POST".equals(method)&&"/api/product-matrix/products/query".equals(uri);
            case "ORDER_LIST"->"POST".equals(method)&&"/api/order-matrix/orders/query".equals(uri);
            case "PUBLISH_CAPABILITIES"->"GET".equals(method)&&("/api/publishing/accounts/"+f.account+"/capabilities").equals(uri);
            case "PRODUCT_DETAIL"->"GET".equals(method)&&uri.startsWith("/api/product-matrix/accounts/"+f.account+"/products/QA-R03-")&&!uri.substring(uri.indexOf("/products/")+10).contains("/");
            default->false;
        };
    }
    private String jsonText(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new BusinessException(500,"R03 QA证据编码失败");}}
    private static final class Fault {
        final long account,operator,expires=Instant.now().toEpochMilli()+120000;final String bearerHash,operation,mode;final int delay;
        final AtomicInteger remaining,applied=new AtomicInteger();volatile boolean reset;
        Fault(long account,long operator,String hash,String operation,String mode,int calls,int delay){this.account=account;this.operator=operator;this.bearerHash=hash;this.operation=operation;this.mode=mode;this.remaining=new AtomicInteger(calls);this.delay=delay;}
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;BodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
        @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new IllegalStateException("R03 QA reads are synchronous");}};}
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
