package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountBatchService;
import com.xianyusmart.service.AccountMatrixService;
import com.xianyusmart.service.ShopProfileSyncService;
import com.xianyusmart.service.ShopRiskSyncService;
import com.xianyusmart.service.AccountLoginBootstrapService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 账号矩阵、店铺画像及风险处理接口。 */
@RestController
@RequestMapping("/api/account-matrix")
public class AccountMatrixController {

    private final AccountMatrixService accountMatrixService;
    private final AccountBatchService accountBatchService;
    private final ShopProfileSyncService shopProfileSyncService;
    private final ShopRiskSyncService shopRiskSyncService;
    private final AccountLoginBootstrapService accountLoginBootstrapService;

    /** 保留给既有单元测试和嵌入式调用；新增能力由完整 Spring 构造器注入。 */
    AccountMatrixController(AccountMatrixService accountMatrixService,
                            AccountBatchService accountBatchService,
                            ShopProfileSyncService shopProfileSyncService) {
        this(accountMatrixService, accountBatchService, shopProfileSyncService, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AccountMatrixController(AccountMatrixService accountMatrixService,
                                   AccountBatchService accountBatchService,
                                   ShopProfileSyncService shopProfileSyncService,
                                   ShopRiskSyncService shopRiskSyncService,
                                   AccountLoginBootstrapService accountLoginBootstrapService) {
        this.accountMatrixService = accountMatrixService;
        this.accountBatchService = accountBatchService;
        this.shopProfileSyncService = shopProfileSyncService;
        this.shopRiskSyncService = shopRiskSyncService;
        this.accountLoginBootstrapService = accountLoginBootstrapService;
    }

    /** 后台登录完成后，一次性上线并刷新当前成员可见账号。 */
    @PostMapping("/session-bootstrap")
    public ResponseEntity<ResultObject<Map<String, Object>>> sessionBootstrap() {
        if (accountLoginBootstrapService == null) throw new IllegalStateException("登录刷新服务未配置");
        return ResponseEntity.accepted().body(ResultObject.success(accountLoginBootstrapService.start()));
    }

    @GetMapping("/summary")
    public ResultObject<Map<String, Object>> summary() {
        return ResultObject.success(accountMatrixService.summary());
    }

    @GetMapping("/accounts")
    public ResultObject<AccountMatrixService.MatrixPage> accounts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String connectionStatus,
            @RequestParam(required = false) String riskSeverity,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String lifecycleState,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return ResultObject.success(accountMatrixService.listAccounts(
                search, connectionStatus, riskSeverity, groupId, lifecycleState, page, pageSize));
    }

    public ResultObject<AccountMatrixService.MatrixPage> accounts(String search,String connectionStatus,String riskSeverity,Long groupId,Integer page,Integer pageSize) {
        return accounts(search,connectionStatus,riskSeverity,groupId,null,page,pageSize);
    }

    @GetMapping("/accounts/{accountId}")
    public ResultObject<Map<String, Object>> accountDetail(@PathVariable Long accountId) {
        return ResultObject.success(accountMatrixService.accountDetail(accountId));
    }

    @GetMapping("/accounts/{accountId}/risks")
    public ResultObject<List<Map<String, Object>>> risks(@PathVariable Long accountId,
                                                         @RequestParam(required = false) String status) {
        return ResultObject.success(accountMatrixService.risks(accountId, status));
    }

    @GetMapping(value = "/accounts/{accountId}/risks/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportRisks(@PathVariable Long accountId) {
        byte[] body = accountMatrixService.exportRisksCsv(accountId).getBytes(StandardCharsets.UTF_8);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("account-" + accountId + "-risks.csv", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /** 由平台适配器或人工导入写入；不把本地推测包装成平台数据。 */
    @PostMapping("/accounts/{accountId}/profile-snapshots")
    public ResultObject<Map<String, Object>> saveProfileSnapshot(
            @PathVariable Long accountId,
            @RequestBody AccountMatrixService.ProfileSnapshotInput input) {
        return ResultObject.success(accountMatrixService.saveProfileSnapshot(accountId, input));
    }

    /** 发起真实店铺画像只读同步；平台采集异步执行，不包含点击、提交或平台写请求。 */
    @PostMapping("/accounts/{accountId}/profile-sync")
    public ResponseEntity<ResultObject<Map<String, Object>>> syncProfile(
            @PathVariable Long accountId,
            @RequestBody ProfileSyncRequest request) {
        Map<String, Object> result = shopProfileSyncService.start(accountId, request == null ? null : request.requestId());
        return "BUSY".equals(result.get("status"))
                ? ResponseEntity.status(409).body(ResultObject.success(result))
                : ResponseEntity.accepted().body(ResultObject.success(result));
    }

    @GetMapping("/accounts/{accountId}/profile-sync")
    public ResultObject<Map<String, Object>> profileSyncStatus(@PathVariable Long accountId,
                                                               @RequestParam(required = false) String requestId) {
        return ResultObject.success(requestId == null || requestId.isBlank()
                ? shopProfileSyncService.latestStatus(accountId)
                : shopProfileSyncService.status(accountId, requestId));
    }

    /** 从平台只读查询处罚记录；失败时保留历史快照并返回明确授权原因。 */
    @PostMapping("/accounts/{accountId}/risk-sync")
    public ResponseEntity<ResultObject<Map<String, Object>>> syncRisks(@PathVariable Long accountId,
                                                                        @RequestBody ProfileSyncRequest request) {
        if (shopRiskSyncService == null) throw new IllegalStateException("处罚同步服务未配置");
        Map<String, Object> result = shopRiskSyncService.sync(accountId, request == null ? null : request.requestId());
        return "BUSY".equals(result.get("status"))
                ? ResponseEntity.status(409).body(ResultObject.success(result))
                : ResponseEntity.ok(ResultObject.success(result));
    }

    /** 写入/更新平台风险快照；此接口不执行申诉、删除或平台侧处置。 */
    @PostMapping("/accounts/{accountId}/risks")
    public ResultObject<Map<String, Object>> upsertRisk(
            @PathVariable Long accountId,
            @RequestBody AccountMatrixService.RiskEventInput input) {
        return ResultObject.success(accountMatrixService.upsertRisk(accountId, input));
    }

    /** 仅更新本地协作进度，绝不直接触发平台申诉。 */
    @PostMapping("/risks/{riskId}/handling")
    public ResultObject<Map<String, Object>> updateRiskHandling(
            @PathVariable Long riskId,
            @RequestBody AccountMatrixService.RiskHandlingInput input) {
        return ResultObject.success(accountMatrixService.updateRiskHandling(riskId, input));
    }

    /** 记录通道健康和授权范围，凭据内容不进入该接口及审计日志。 */
    @PutMapping("/accounts/{accountId}/access-channels/{channelCode}")
    public ResultObject<Map<String, Object>> upsertAccessChannel(
            @PathVariable Long accountId,
            @PathVariable String channelCode,
            @RequestBody AccountMatrixService.AccessChannelInput input) {
        return ResultObject.success(accountMatrixService.upsertAccessChannel(accountId, channelCode, input));
    }

    @PostMapping("/batches/preview")
    public ResultObject<AccountBatchService.Preview> previewBatch(@RequestBody AccountBatchService.Request request) {
        return ResultObject.success(accountBatchService.preview(request));
    }

    @PostMapping("/batches/{operation}/create")
    public ResultObject<Map<String, Object>> createBatch(@PathVariable String operation,
                                                         @RequestBody AccountBatchService.Request request) {
        if (request.operationType() == null || !operation.equalsIgnoreCase(request.operationType())) {
            throw new BusinessException(400, "路径操作类型与请求不一致");
        }
        return ResultObject.success(accountBatchService.create(request));
    }

    @GetMapping("/batches/{batchId}")
    public ResultObject<Map<String, Object>> batch(@PathVariable String batchId) {
        return ResultObject.success(accountBatchService.batch(batchId));
    }

    public record ProfileSyncRequest(String requestId) {}
}
