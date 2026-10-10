package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.impl.ItemServiceImpl;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Durable ON_SALE enumeration; remote reads happen outside database transactions. */
@Service
public class ProductSyncTaskService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AccountAccessService access;
    private final AccountAutomationPolicy policy;
    private final ItemServiceImpl items;
    private final GoodsInfoService goods;
    private final ItemDetailSyncService details;
    private final ProductSyncStateService dataset;
    private final OperationLogService audits;
    private final ProductSyncQaFacts qa;
    private final ItemDetailSnapshotService snapshots;
    private final com.xianyusmart.mapper.SysUserMapper users;
    private final PlatformPermissionService permissions;
    private final TransactionTemplate transaction;
    private final ExecutorService executor = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"product-sync-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public ProductSyncTaskService(JdbcTemplate jdbc,ObjectMapper json,AccountAccessService access,AccountAutomationPolicy policy,ItemServiceImpl items,GoodsInfoService goods,ItemDetailSyncService details,ProductSyncStateService dataset,OperationLogService audits,ProductSyncQaFacts qa,ItemDetailSnapshotService snapshots,com.xianyusmart.mapper.SysUserMapper users,PlatformPermissionService permissions,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.policy=policy;this.items=items;this.goods=goods;this.details=details;this.dataset=dataset;this.audits=audits;this.qa=qa;this.snapshots=snapshots;this.users=users;this.permissions=permissions;this.transaction=new TransactionTemplate(manager);
        // Account locking must be followed by a fresh committed request/slot read.
        // REPEATABLE_READ would keep the policy read's earlier snapshot and reject valid replays.
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        jdbc.update("""
            UPDATE xianyu_product_sync_task SET status='INTERRUPTED',stage='INTERRUPTED',
              active_account_id=NULL,worker_token=NULL,row_version=row_version+1,completed_time=NOW(3),
              last_error_code='WORKER_RESTARTED',last_error_message='服务重启，原同步任务已中断',
              recovery_hint='核对已保存数据后使用新请求重新同步'
            WHERE status='RUNNING'
            """);
    }
    public Map<String,Object> start(AllItemsReqDTO request) {
        try { return startAllowed(request); }
        catch(org.springframework.dao.DuplicateKeyException e) { throw new BusinessException(409,"requestId已用于其他账号或该账号已有同步任务"); }
    }
    private Map<String,Object> startAllowed(AllItemsReqDTO request) {
        Long tenant=TenantContext.get(), account=request.getXianyuAccountId(), operator=UserContext.getUserId();
        String username=UserContext.getUsername(), requestId=request.getRequestId();
        if(tenant==null||operator==null)throw new BusinessException(403,"缺少操作者上下文");
        if(account==null||account<=0)throw new BusinessException(400,"账号ID无效");
        AccountScopeContext.Scope scope=AccountScopeContext.get();
        if(scope==null)throw new BusinessException(403,"缺少账号授权范围");
        access.requireAccess(account);
        if(!"ON_SALE".equals(request.getScope()))throw new BusinessException(400,"当前只支持官方在售列表范围");
        if(requestId==null||!requestId.equals(requestId.trim())||requestId.isBlank()||requestId.length()>80)throw new BusinessException(400,"requestId无效");
        int pageSize=request.getPageSize()==null?20:request.getPageSize(),maxPages=request.getMaxPages()==null?100:request.getMaxPages();
        if(pageSize<1||pageSize>100||maxPages<1||maxPages>100)throw new BusinessException(400,"分页范围必须为1至100");
        String payloadHash=hash(account+":ON_SALE:"+pageSize+":"+maxPages), token=UUID.randomUUID().toString();
        Map<String,Object> accepted=transaction.execute(status->policy.guarded(account,AccountAutomationPolicy.Action.SYNC,()->{
            jdbc.queryForMap("SELECT id FROM xianyu_account WHERE tenant_id=? AND id=? FOR UPDATE",tenant,account);
            var previous=jdbc.queryForList("SELECT * FROM xianyu_product_sync_task WHERE tenant_id=? AND request_id=?",tenant,requestId);
            if(!previous.isEmpty()) {var row=previous.getFirst();if(!payloadHash.equals(row.get("payload_hash")))throw new BusinessException(409,"requestId已用于其他载荷");return project(row,true);}
            // The account row already serializes acceptance for this account.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_product_sync_task WHERE tenant_id=? AND active_account_id=?",Integer.class,tenant,account)>0)throw new BusinessException(409,"该账号已有进行中的同步任务");
            jdbc.update("""
              INSERT INTO xianyu_product_sync_task(tenant_id,xianyu_account_id,request_id,payload_hash,page_size,max_pages,active_account_id,worker_token,progress_json)
              VALUES(?,?,?,?,?,?,?,?,?)
              """,tenant,account,requestId,payloadHash,pageSize,maxPages,account,token,write(Map.of("operatorUserId",operator,"operatorUsername",username==null?"":username)));
            var audit=new com.xianyusmart.entity.XianyuOperationLog();audit.setXianyuAccountId(account);audit.setOperationType("PRODUCT_SYNC_ACCEPTED");audit.setOperationModule("GOODS");audit.setOperationStatus(1);audit.setTargetType("ACCOUNT");audit.setTargetId(String.valueOf(account));audit.setRequestId(requestId);audit.setOutcomeState("ACCEPTED");audit.setDataSource("LOCAL_COMMAND");audit.setRequestParams(write(Map.of("scope","ON_SALE","pageSize",pageSize,"maxPages",maxPages)));audits.logRequired(audit);
            return project(jdbc.queryForMap("SELECT * FROM xianyu_product_sync_task WHERE tenant_id=? AND request_id=?",tenant,requestId),false);
        }));
        if(Boolean.TRUE.equals(accepted.get("replayed")))return accepted;
        long taskId=((Number)accepted.get("taskId")).longValue();
        try {executor.execute(()->run(tenant,account,operator,username,taskId,token,pageSize,maxPages,requestId,scope));}
        catch(RejectedExecutionException e){finish(new Worker(tenant,account,taskId,token),"FAILED","NONE","WORKER_BUSY","同步队列暂不可用，请稍后重新提交");return read(taskId);}
        return accepted;
    }
    public List<Map<String,Object>> list(Long account) {
        if(account==null||account<=0)throw new BusinessException(400,"账号ID无效");access.requireAccess(account);
        return jdbc.queryForList("SELECT * FROM xianyu_product_sync_task WHERE tenant_id=? AND xianyu_account_id=? ORDER BY id DESC LIMIT 20",TenantContext.get(),account).stream().map(row->project(row,false)).toList();
    }
    public Map<String,Object> read(long id) {
        var rows=jdbc.queryForList("SELECT * FROM xianyu_product_sync_task WHERE tenant_id=? AND id=?",TenantContext.get(),id);
        if(rows.isEmpty())throw new BusinessException(404,"同步任务不存在");access.requireAccess(((Number)rows.getFirst().get("xianyu_account_id")).longValue());return project(rows.getFirst(),false);
    }
    public Map<String,Object> interrupt(long id) {
        var task=read(id);Long tenant=TenantContext.get();
        jdbc.update("UPDATE xianyu_product_sync_task SET status='INTERRUPTED',stage='INTERRUPTED',active_account_id=NULL,worker_token=NULL,row_version=row_version+1,completed_time=NOW(3),last_error_code='USER_INTERRUPTED',recovery_hint='核对已保存数据后使用新请求重新同步' WHERE tenant_id=? AND id=? AND status='RUNNING'",tenant,id);
        return read(id);
    }
    private void run(Long tenant,Long account,Long operator,String username,long id,String token,int size,int max,String requestId,AccountScopeContext.Scope scope) {
        TenantContext.set(tenant);UserContext.set(operator,username,tenant);AccountScopeContext.set(scope.unrestricted(),scope.accountIds());Worker worker=new Worker(tenant,account,id,token);
        List<ItemDTO> found=new ArrayList<>();Set<String> seen=new LinkedHashSet<>();List<String> saved=new ArrayList<>();List<String> detailed=new ArrayList<>();
        boolean terminal=false;String errorCode=null;Long reportedTotal=null;
        try {
            for(int page=1;page<=max;page++) {
                requireCurrentAuthority(account,operator,tenant,scope);
                worker.update("stage='LISTING',pages_requested=pages_requested+1");
                var req=new ItemListReqDTO();req.setCookieId(String.valueOf(account));req.setPageNumber(page);req.setPageSize(size);
                int requestedPage=page;
                var result=policy.guarded(account,AccountAutomationPolicy.Action.SYNC,()->qa.handles(account)?com.xianyusmart.common.ResultObject.success(qa.page(account,requestedPage,size)):items.fetchOnSalePage(req));
                var data=result.getData();
                if(result.getCode()!=200||data==null||!Boolean.TRUE.equals(data.getSuccess())||!Boolean.TRUE.equals(data.getStructureValidated())) {errorCode=data!=null&&data.getErrorCode()!=null?data.getErrorCode():"LIST_UNAVAILABLE";break;}
                if(data.getReportedTotal()!=null) {
                    if(reportedTotal!=null&&!reportedTotal.equals(data.getReportedTotal())){errorCode="PAGINATION_TOTAL_CHANGED";break;}
                    reportedTotal=data.getReportedTotal();worker.update("reported_total=?",reportedTotal);
                }
                worker.update("pages_completed=pages_completed+1");
                boolean added=false;
                for(ItemDTO item:data.getItems())if(seen.add(item.getId())){found.add(item);added=true;}
                worker.update("discovered_count=?",seen.size());
                if(Boolean.TRUE.equals(data.getTerminalPage())) {if(reportedTotal!=null&&reportedTotal!=seen.size()){errorCode="PAGINATION_TOTAL_CONFLICT";break;}worker.update("progress_json=JSON_SET(COALESCE(progress_json,'{}'),'$.terminationEvidence',?)",data.getTerminationEvidence());terminal=true;break;}
                if(data.getItems().isEmpty()){errorCode="PAGINATION_UNVERIFIED";break;}
                if(!added){errorCode="PAGINATION_REPEATED";break;}
            }
            if(!terminal&&errorCode==null)errorCode="PAGE_LIMIT";
            worker.update("stage='SAVING',list_completed_time="+(terminal?"NOW(3)":"NULL")+",coverage=?",terminal?(found.isEmpty()?"EMPTY_VERIFIED":"FULL"):found.isEmpty()?"NONE":"PARTIAL");
            for(ItemDTO item:found) {
                requireCurrentAuthority(account,operator,tenant,scope);
                Boolean success;
                try { success=transaction.execute(status->policy.guarded(account,AccountAutomationPolicy.Action.SYNC,()->{requireCurrentAuthority(account,operator,tenant,scope);worker.lock();return qa.handles(account)&&qa.saveFails(account,item.getId())?false:goods.saveOrUpdateGoodsInfo(item,account);})); }
                catch(WorkerInterrupted e){throw e;}
                catch(BusinessException e){if(Integer.valueOf(403).equals(e.getCode()))throw e;success=false;}
                catch(Exception e){success=false;}
                if(Boolean.TRUE.equals(success)){saved.add(item.getId());worker.update("saved_count=saved_count+1");}
                else worker.update("save_failed_count=save_failed_count+1");
            }
            worker.update("stage='DETAILS',save_completed_time="+(saved.size()==found.size()&&(!found.isEmpty()||terminal)?"NOW(3)":"NULL"));
            for(String itemId:saved) {
                requireCurrentAuthority(account,operator,tenant,scope);
                var detail=policy.guarded(account,AccountAutomationPolicy.Action.SYNC,()->ProductSyncCommitGuard.withAuthority(()->{requireCurrentAuthority(account,operator,tenant,scope);worker.lock();},()->qa.handles(account)?qa.detail(account,itemId,snapshots):details.syncSingleItemWithResult(account,itemId)));
                if(detail.isSuccess())detailed.add(itemId);
                worker.update(detail.isSuccess()?"detail_success_count=detail_success_count+1":"detail_failed_count=detail_failed_count+1");
            }
            worker.update("detail_completed_time="+(detailed.size()==saved.size()&&(!saved.isEmpty()||terminal&&found.isEmpty())?"NOW(3)":"NULL"));
            String coverage=terminal?(found.isEmpty()?"EMPTY_VERIFIED":"FULL"):found.isEmpty()?"NONE":"PARTIAL";
            var row=worker.row();int saveFailed=((Number)row.get("save_failed_count")).intValue(),detailFailed=((Number)row.get("detail_failed_count")).intValue();
            String outcome=found.isEmpty()&&!terminal?"FAILED":(!terminal||saveFailed>0||detailFailed>0)?"PARTIAL":"SUCCEEDED";
            // Dataset and touched rows update under the same durable task authority.
            String finalError=errorCode==null?(saveFailed>0?"SAVE_FAILED":detailFailed>0?"DETAIL_UNAVAILABLE":null):errorCode;
            transaction.executeWithoutResult(status->{requireCurrentAuthority(account,operator,tenant,scope);worker.lock();if(!"FAILED".equals(outcome))dataset.completed(account,requestId,coverage,outcome,detailed,finalError);else dataset.failed(account,requestId,finalError==null?"LIST_UNAVAILABLE":finalError,"官方在售列表暂不可用");});
            finish(worker,outcome,coverage,errorCode==null?(saveFailed>0?"SAVE_FAILED":detailFailed>0?"DETAIL_UNAVAILABLE":null):errorCode,terminal?"在售列表已验证完整；其他官方状态范围尚未适配":"在售列表未完整验证，请核对任务阶段后重新同步");
        }catch(WorkerInterrupted e){/* authority already revoked; never overwrite the terminal task */}
        catch(Exception e){String code=e instanceof BusinessException b&&Integer.valueOf(403).equals(b.getCode())?"ACCESS_REVOKED":"SYNC_UNAVAILABLE";finish(worker,"FAILED",seen.isEmpty()?"NONE":"PARTIAL",code,"同步未完成，已保存数据保留，请核对权限与任务阶段后重试");}
        finally{AccountScopeContext.clear();UserContext.clear();TenantContext.clear();}
    }
    private void requireCurrentAuthority(Long account,Long operator,Long tenant,AccountScopeContext.Scope original) {
        var user=users.selectById(operator);
        if(user==null||!Integer.valueOf(1).equals(user.getStatus())||(!"ADMIN".equals(user.getRole())&&!Objects.equals(tenant,user.getTenantId()))||!permissions.hasPermission(operator,PermissionCatalog.ACTION_GOODS_WRITE))throw new BusinessException(403,"同步操作者权限已撤销");
        var current=access.loadScope(user);
        boolean allowed=(original.unrestricted()||original.accountIds().contains(account))&&(current.unrestricted()||current.accountIds().contains(account));
        if(!allowed)throw new BusinessException(403,"同步账号授权已撤销");
        AccountScopeContext.set(false,Set.of(account));access.requireAccess(account);
    }
    private void finish(Worker worker,String status,String coverage,String error,String message) {
        try {
            String recovery=switch(error==null?"":error) {
                case "VERIFICATION_REQUIRED" -> "请在官方闲鱼客户端完成安全验证后再同步";
                case "PLATFORM_PERMISSION_DENIED" -> "请核对官方店铺接口授权；重新扫码不保证解除权限限制";
                case "RATE_LIMITED" -> "请等待平台限流解除后再提交新请求";
                case "CREDENTIAL_EXPIRED", "CREDENTIAL_MISSING" -> "请核对该店铺凭据状态后按账号恢复入口处理";
                case "EMPTY_RESPONSE", "UPSTREAM_UNAVAILABLE" -> "官方响应暂不可用，请稍后重试；此结果不代表空店铺或凭据失效";
                case "PAGINATION_UNVERIFIED" -> "分页终止依据缺失，保留缓存；需可靠官方分页契约才能确认完整";
                default -> "核对已保存数据后使用新请求重新同步";
            };
            var row=worker.row();String errorStage=error==null?"COMPLETED":error.startsWith("SAVE")?"SAVING":error.startsWith("DETAIL")?"DETAILS":error.equals("SYNC_UNAVAILABLE")?String.valueOf(row.get("stage")):"LISTING";
            var result=project(row,false);result.put("stage",errorStage);result.put("status",status);result.put("coverage",coverage);result.put("last_error_code",error);result.put("last_error_message",message);
            result.put("recovery_hint",recovery);
            worker.update("status=?,stage=?,coverage=?,active_account_id=NULL,worker_token=NULL,completed_time=NOW(3),last_error_code=?,last_error_message=?,recovery_hint=?,result_json=?",status,errorStage,coverage,error,message,recovery,write(result));
        }catch(WorkerInterrupted ignored){}
    }
    private final class Worker {
        final Long tenant,account;final long id;final String token;final AtomicLong version=new AtomicLong();
        Worker(Long tenant,Long account,long id,String token){this.tenant=tenant;this.account=account;this.id=id;this.token=token;}
        Map<String,Object> row(){var r=jdbc.queryForMap("SELECT * FROM xianyu_product_sync_task WHERE tenant_id=? AND id=?",tenant,id);if(!"RUNNING".equals(r.get("status"))||!token.equals(r.get("worker_token"))||((Number)r.get("row_version")).longValue()!=version.get())throw new WorkerInterrupted();return r;}
        void lock(){var r=jdbc.queryForMap("SELECT status,worker_token,row_version FROM xianyu_product_sync_task WHERE tenant_id=? AND id=? FOR UPDATE",tenant,id);if(!"RUNNING".equals(r.get("status"))||!token.equals(r.get("worker_token"))||((Number)r.get("row_version")).longValue()!=version.get())throw new WorkerInterrupted();}
        void update(String assignments,Object... values){List<Object> args=new ArrayList<>(Arrays.asList(values));args.add(tenant);args.add(id);args.add(token);args.add(version.get());int changed=jdbc.update("UPDATE xianyu_product_sync_task SET "+assignments+",row_version=row_version+1 WHERE tenant_id=? AND id=? AND status='RUNNING' AND worker_token=? AND row_version=?",args.toArray());if(changed!=1)throw new WorkerInterrupted();version.incrementAndGet();}
    }
    private static final class WorkerInterrupted extends RuntimeException {}
    private Map<String,Object> project(Map<String,Object> row,boolean replayed){
        var result=new LinkedHashMap<String,Object>();result.put("taskId",row.get("id"));result.put("accountId",row.get("xianyu_account_id"));result.put("requestId",row.get("request_id"));result.put("scope","ON_SALE");result.put("scopeCode","ON_SALE");result.put("scopeLabel","在售商品");result.put("source","PLATFORM_WEB");result.put("allStatusesCovered",false);result.put("unsupportedScopes",List.of("OFF_SHELF","SOLD","OTHER"));result.put("platformCapability","DEGRADED_PLATFORM");result.put("replayed",replayed);
        result.put("listCoverage",row.get("coverage"));
        int saved=((Number)row.get("saved_count")).intValue(),success=((Number)row.get("detail_success_count")).intValue();
        result.put("detailsCoverage", "SUCCEEDED".equals(row.get("status")) && "EMPTY_VERIFIED".equals(row.get("coverage"))?"EMPTY_VERIFIED":saved>0&&success==saved?"FULL":success>0?"PARTIAL":"NONE");
        try {var metadata=json.readTree(String.valueOf(row.get("progress_json")));result.put("terminationEvidence",metadata.path("terminationEvidence").asText("NOT_PROVIDED"));}catch(Exception ignored){result.put("terminationEvidence","NOT_PROVIDED");}
        for(String key:List.of("status","stage","coverage","pages_requested","pages_completed","discovered_count","saved_count","save_failed_count","detail_success_count","detail_failed_count","started_time","list_completed_time","save_completed_time","detail_completed_time","updated_time","completed_time","last_error_code","last_error_message","recovery_hint"))result.put(key,row.get(key));return result;
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("同步元数据无法序列化",e);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    @PreDestroy public void close(){executor.shutdownNow();}
}
