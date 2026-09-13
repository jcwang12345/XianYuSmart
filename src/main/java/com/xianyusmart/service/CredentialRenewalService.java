package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.service.notification.RenewalImageStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** A bounded, per-account scan/verify/reconnect workflow. No public credential endpoint. */
@Service
@Slf4j
public class CredentialRenewalService {
    private static final long REMINDER_MS = 6 * 60 * 60 * 1000L;
    private final XianyuAccountMapper accounts;
    private final QRLoginService qr;
    private final ObjectProvider<WebSocketService> websocket;
    private final ObjectProvider<EmailNotifyService> email;
    private final NotificationCenterService notifications;
    private final RenewalImageStore images;
    private final Executor executor;
    private final Map<Long, Flow> flows = new ConcurrentHashMap<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<WebSocketTokenService> tokenService;
    private static final class Flow {
        Long tenant, account; String note, session, image; int generation;
        long reminderAt, expiresAt, nextCheck; boolean confirmed, terminal;
        String mailBody; int mailAttempts; long nextMail;
    }
    public CredentialRenewalService(XianyuAccountMapper accounts, QRLoginService qr,
            ObjectProvider<WebSocketService> websocket, ObjectProvider<EmailNotifyService> email,
            NotificationCenterService notifications, RenewalImageStore images,
            @Qualifier("taskExecutor") Executor executor) {
        this.accounts=accounts;this.qr=qr;this.websocket=websocket;this.email=email;
        this.notifications=notifications;this.images=images;this.executor=executor;
    }
    /** Called by the existing expiry escalation only, not every network disconnect. */
    public void request(Long accountId) {
        if (accountId == null || !running.add(accountId)) return;
        Long tenant = TenantContext.get();
        try {
            executor.execute(() -> {
                try {
                    if (tenant != null) TenantContext.set(tenant);
                    XianyuAccount account = accounts.selectById(accountId);
                    if (account == null || account.getTenantId() == null || !Integer.valueOf(1).equals(account.getStatus())) return;
                    TenantContext.set(account.getTenantId());
                    Flow old = flows.get(accountId);
                    if (old != null && old.reminderAt > System.currentTimeMillis()) return;
                    Flow flow = new Flow(); flow.tenant=account.getTenantId();flow.account=accountId;
                    flow.note=(account.getAccountNote()==null ? "闲鱼账号" : account.getAccountNote()) + "（ID " + accountId + "）";
                    flow.reminderAt=System.currentTimeMillis()+REMINDER_MS;
                    flows.put(accountId,flow);
                    if(tokenService!=null && tokenService.getObject().getPendingCaptchaUrl(accountId)!=null) {
                        flow.terminal=true;
                        notice(flow,"ACCOUNT_VERIFICATION_REQUIRED","账号需要官方安全验证","账号："+flow.note+"\n请先在官方页面或闲鱼 App 完成安全验证；系统不会为此反复生成登录二维码。",null);
                        return;
                    }
                    generate(flow);
                } catch (Exception e) {
                    Flow flow=flows.get(accountId);
                    if(flow!=null) {
                        flow.terminal=true;
                        notice(flow,"CREDENTIAL_EXPIRED","续期二维码准备失败","账号："+flow.note+"\n暂时无法生成或投递二维码，请稍后重试；不会发送无效二维码。",null);
                    }
                    log.warn("续期通知准备失败: accountId={}, type={}",accountId,e.getClass().getSimpleName());
                }
                finally { TenantContext.clear();running.remove(accountId); }
            });
        } catch (RuntimeException e) { running.remove(accountId); throw e; }
    }
    private void generate(Flow flow) {
        images.remove(flow.image);
        flow.generation++;
        long started = System.currentTimeMillis();
        boolean canMail = email.getObject().isEmailConfigured() && email.getObject().isCookieExpireNotifyEnabled();
        if (!canMail && !notifications.hasRenewalImageChannel()) {
            flow.terminal=true;
            notice(flow,"CREDENTIAL_EXPIRED","需要重新扫码登录", "自动恢复未成功。未配置二维码接收渠道，请在账号管理中重新扫码；配置企业微信凭证失效通知或邮件后可直接接收二维码。",null);
            return;
        }
        var result = qr.generateQRCode(flow.account);
        // The QR session itself expires after five minutes; use a conservative four-minute delivery window.
        flow.expiresAt=started+240000;
        if (!result.isSuccess() || result.getSessionId()==null || flow.expiresAt <= System.currentTimeMillis()) {
            flow.terminal=true;
            notice(flow,"CREDENTIAL_EXPIRED","扫码续期二维码暂时无法生成", "平台暂未返回可用二维码，请稍后在账号管理中重试；本次不附带无效图片。",null);
            return;
        }
        flow.session=result.getSessionId();
        flow.image=images.put(flow.tenant,flow.account,result.getQrCodeUrl(),flow.expiresAt);
        String until=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(flow.expiresAt));
        String body="账号："+flow.note+"\n影响：AI 回复、订单同步和发货可能受阻。\n操作：使用此账号的闲鱼 App 扫描本条二维码并确认登录。\n请于北京时间 "+until+" 前扫码（平台可能提前失效），以最新二维码为准。\n第 "+flow.generation+" / 3 次换码；本轮过期最多自动换码两次，之后停止刷屏，持续异常六小时后再次提醒。\n扫码后自动核对账号、保存凭证并尝试恢复连接；无需登录管理网页。登录二维码请勿转发。";
        notifications.dispatch("CREDENTIAL_EXPIRED",flow.account,"需扫码续期 · "+flow.note,body,Map.of("_renewalBatch",flow.image));
        notifications.dispatch("CREDENTIAL_EXPIRED",flow.account,"续期二维码 · "+flow.note,body,Map.of("_renewalBatch",flow.image,"_renewalImage",flow.image));
        var entry=images.get(flow.image,flow.tenant,flow.account);
        flow.mailBody=null;flow.mailAttempts=0;
        if (canMail && entry!=null && email.getObject().sendCredentialRenewalMail("需扫码续期 · "+flow.note,body,entry.png())!=null) {
            flow.mailBody=body;flow.mailAttempts=1;flow.nextMail=System.currentTimeMillis()+30000;
        }
        flow.nextCheck=System.currentTimeMillis()+10000;
    }
    @Scheduled(fixedDelay=10000, initialDelay=30000)
    public void checkFlows() {
        for (Flow flow : flows.values()) {
            if (flow.terminal || flow.nextCheck > System.currentTimeMillis() || !running.add(flow.account)) continue;
            try { executor.execute(() -> {
                try { TenantContext.set(flow.tenant); check(flow); }
                catch(Exception e) { flow.nextCheck=System.currentTimeMillis()+60000;log.warn("续期状态检查失败: accountId={}, type={}",flow.account,e.getClass().getSimpleName()); }
                finally { TenantContext.clear();running.remove(flow.account); }
            }); } catch(RuntimeException e) { running.remove(flow.account); }
        }
        flows.entrySet().removeIf(e -> e.getValue().terminal && e.getValue().reminderAt < System.currentTimeMillis()-REMINDER_MS);
    }
    @Scheduled(fixedDelay=60000, initialDelay=120000)
    public void remindExpiredAccounts() {
        for(var account:accounts.selectExpiredCredentialAccounts()) {
            try { TenantContext.set(account.getTenantId());request(account.getId()); }
            finally { TenantContext.clear(); }
        }
    }
    private void check(Flow flow) {
        XianyuAccount account=accounts.selectById(flow.account);
        if(account==null || !Integer.valueOf(1).equals(account.getStatus())) { images.remove(flow.image);flow.terminal=true;return; }
        var status=qr.getSessionStatus(flow.session);
        if(flow.mailBody!=null && flow.mailAttempts<3 && System.currentTimeMillis()>=flow.nextMail
                && ("pending".equals(status.getStatus()) || "scanned".equals(status.getStatus()))) {
            var entry=images.get(flow.image,flow.tenant,flow.account);
            if(entry!=null) {
                flow.mailAttempts++;flow.nextMail=System.currentTimeMillis()+60000;
                if(email.getObject().sendCredentialRenewalMail("需扫码续期 · "+flow.note,flow.mailBody,entry.png())==null)flow.mailBody=null;
            }
        }
        if (flow.confirmed || ("confirmed".equals(status.getStatus()) && flow.account.equals(status.getAccountId()))) {
            images.remove(flow.image);
            if(!flow.confirmed) { flow.confirmed=true;websocket.getObject().restartAfterCredentialUpdate(flow.account); }
            if(websocket.getObject().isConnected(flow.account)) {
                flow.terminal=true;
                notice(flow,"ACCOUNT_RECOVERED","扫码续期成功，消息连接已恢复","账号："+flow.note+"\n已核对扫码账号并保存凭证，消息监听已恢复。待处理任务将按安全重试规则继续；需人工核对的订单不会重复发货。",null);
            } else if(System.currentTimeMillis()>flow.expiresAt+300000) {
                flow.terminal=true;
                notice(flow,"ACCOUNT_OFFLINE","凭证已更新，连接尚未恢复","账号："+flow.note+"\n扫码和凭证保存成功，但尚未确认消息连接恢复。系统将继续退避重连，请勿重复扫码或重复发货。",null);
            }
            flow.nextCheck=System.currentTimeMillis()+30000;
            return;
        }
        if("verification_required".equals(status.getStatus()) || "error".equals(status.getStatus()) || "cancelled".equals(status.getStatus())) {
            flow.terminal=true;images.remove(flow.image);
            notice(flow,"verification_required".equals(status.getStatus())?"ACCOUNT_VERIFICATION_REQUIRED":"CREDENTIAL_EXPIRED",
                    "扫码续期未完成", "账号："+flow.note+"\n"+status.getMessage()+"\n未确认恢复，请核对扫码账号；如有官方安全验证，请在闲鱼 App 完成后再重新扫码。",null);
            return;
        }
        if("expired".equals(status.getStatus()) || "not_found".equals(status.getStatus()) || System.currentTimeMillis()>flow.expiresAt) {
            if(flow.generation<3 && !"scanned".equals(status.getStatus())) generate(flow);
            else { flow.terminal=true;images.remove(flow.image);
                notice(flow,"CREDENTIAL_EXPIRED","本轮续期二维码已过期","账号："+flow.note+"\n尚未完成续期，本轮已停止自动换码，旧二维码请勿再用。持续异常六小时后会再次提醒；需要立即恢复时可在账号管理重新生成二维码。",null); }
        }
    }
    private void notice(Flow flow,String type,String title,String body,byte[] png) {
        notifications.dispatch(type,flow.account,title,body,Map.of("_renewalBatch",flow.image==null?"fallback-"+flow.reminderAt:flow.image+"-"+title));
        email.getObject().sendCredentialRenewalMail(title,body,png);
    }
}
