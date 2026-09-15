package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.FixedDeliveryTemplatePreviewReqDTO;
import com.xianyusmart.controller.dto.FixedDeliveryTemplateReqDTO;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuFixedDeliveryTemplateMapper;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;

/**
 * 固定内容模板管理
 */
@Service
public class FixedDeliveryTemplateService {

    private final XianyuFixedDeliveryTemplateMapper templateMapper;
    private final XianyuAccountMapper accountMapper;
    private final BuyerMessageService buyerMessageService;
    private final SharedAccountLinkMapper sharedAccountLinkMapper;
    private final OperationLogService operationLogService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FixedDeliveryTemplateService(XianyuFixedDeliveryTemplateMapper templateMapper,
                                        XianyuAccountMapper accountMapper,
                                        BuyerMessageService buyerMessageService,
                                        SharedAccountLinkMapper sharedAccountLinkMapper,
                                        OperationLogService operationLogService,
                                        JdbcTemplate jdbcTemplate,
                                        ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.accountMapper = accountMapper;
        this.buyerMessageService = buyerMessageService;
        this.sharedAccountLinkMapper = sharedAccountLinkMapper;
        this.operationLogService = operationLogService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResultObject<XianyuFixedDeliveryTemplate> save(FixedDeliveryTemplateReqDTO request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()
                || request.getRequestId().trim().length() > 80) {
            return ResultObject.failed(400, "保存模板需要有效的 requestId");
        }
        try {
            List<Long> accountIds = normalizeAccountIds(request.getXianyuAccountIds(), request.getXianyuAccountId());
            if (accountIds.isEmpty()) {
                throw new IllegalArgumentException("至少选择一个适用账号");
            }
            accountIds.forEach(this::requireOwnedAccount);
            Long primaryAccountId = accountIds.get(0);
            String name = normalizeRequired(request.getTemplateName(), "模板名称", 100);
            String content = normalizeRequired(request.getDeliveryContent(), "全部发货内容", 200);
            String messageTemplate = buyerMessageService.normalizeDeliveryMessageTemplate(
                    request.getMessageTemplate());
            String renderedPreview = buyerMessageService.renderVariables(
                    messageTemplate, "示例会员", "202607240001", content);
            if (renderedPreview.length() > 200) {
                throw new IllegalArgumentException("发送预览不能超过200个字符");
            }
            XianyuFixedDeliveryTemplate replay = findReplay(request.getRequestId().trim(), primaryAccountId);
            if (replay != null) {
                XianyuFixedDeliveryTemplate related = withRelations(replay);
                boolean samePayload = Objects.equals(name, related.getTemplateName())
                        && Objects.equals(content, related.getDeliveryContent())
                        && Objects.equals(messageTemplate, related.getMessageTemplate())
                        && new java.util.LinkedHashSet<>(accountIds)
                        .equals(new java.util.LinkedHashSet<>(related.getXianyuAccountIds()));
                if (!samePayload) return ResultObject.failed(409, "requestId 已用于不同的模板载荷");
                return ResultObject.success(related, "幂等重放：模板已保存");
            }

            XianyuFixedDeliveryTemplate template;
            Map<String, Object> before = new LinkedHashMap<>();
            if (request.getId() == null) {
                template = new XianyuFixedDeliveryTemplate();
                template.setXianyuAccountId(primaryAccountId);
                template.setTenantId(accountMapper.selectById(primaryAccountId).getTenantId());
                template.setTemplateVersion(1L);
            } else {
                if (templateMapper.findOwnedById(primaryAccountId, request.getId()) == null) {
                    return ResultObject.failed("固定内容模板不存在或不适用于当前账号");
                }
                template = templateMapper.lockById(request.getId());
                if (template == null) {
                    return ResultObject.failed("固定内容模板不存在");
                }
                before = auditView(template, sharedAccountLinkMapper.selectFixedTemplateAccounts(template.getId()));
                template.setTemplateVersion((template.getTemplateVersion() == null ? 1L
                        : template.getTemplateVersion()) + 1L);
            }
            template.setTemplateName(name);
            template.setXianyuAccountId(primaryAccountId);
            template.setDeliveryContent(content);
            template.setMessageTemplate(messageTemplate);
            if (template.getId() == null) {
                templateMapper.insert(template);
            } else {
                templateMapper.updateById(template);
            }
            replaceAccounts(template.getId(), template.getTenantId(), accountIds);
            template.setXianyuAccountIds(accountIds);
            insertVersion(template, accountIds, request.getRequestId().trim());
            auditSave(template, before, auditView(template, accountIds), request.getRequestId().trim(),
                    request.getId() == null);
            template.setReferenceCount(templateMapper.countReferencedConfigs(template.getId()));
            return ResultObject.success(template);
        } catch (Exception e) {
            try { TransactionAspectSupport.currentTransactionStatus().setRollbackOnly(); }
            catch (Exception ignored) { }
            return ResultObject.failed("保存固定内容模板失败: " + e.getMessage());
        }
    }

    public ResultObject<List<XianyuFixedDeliveryTemplate>> list(Long accountId, String keyword) {
        try {
            requireOwnedAccount(accountId);
            List<XianyuFixedDeliveryTemplate> templates = templateMapper.findByAccountId(accountId);
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
            templates = templates.stream().filter(template -> normalizedKeyword.isEmpty()
                            || containsIgnoreCase(template.getTemplateName(), normalizedKeyword)
                            || containsIgnoreCase(template.getDeliveryContent(), normalizedKeyword))
                    .map(this::withRelations).toList();
            return ResultObject.success(templates);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    public ResultObject<List<XianyuFixedDeliveryTemplate>> list(Long accountId) {
        return list(accountId, null);
    }

    @Transactional
    public ResultObject<Void> delete(Long accountId, Long id, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.trim().length() > 80) {
            return ResultObject.failed(400, "删除模板需要有效的 requestId");
        }
        try {
            requireOwnedAccount(accountId);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
        XianyuFixedDeliveryTemplate template = findOwnedTemplate(accountId, id);
        if (template == null) {
            return ResultObject.failed("固定内容模板不存在");
        }
        if (templateMapper.countReferencedConfigs(id) > 0) {
            return ResultObject.failed("模板正在被商品使用，请先为商品更换模板");
        }
        XianyuOperationLog audit = auditBase(template, "FIXED_TEMPLATE_DELETE", "删除固定内容模板", requestId.trim());
        audit.setFieldDiffJson(json(Map.of("before", auditView(template,
                sharedAccountLinkMapper.selectFixedTemplateAccounts(id)), "after", Map.of())));
        operationLogService.logRequired(audit);
        templateMapper.deleteById(id);
        return ResultObject.success(null);
    }

    public ResultObject<Void> delete(Long accountId, Long id) {
        return delete(accountId, id, null);
    }

    public ResultObject<Map<String, Object>> preview(FixedDeliveryTemplatePreviewReqDTO request) {
        if (request == null || request.getXianyuAccountId() == null) {
            return ResultObject.failed(400, "请选择账号");
        }
        try {
            requireOwnedAccount(request.getXianyuAccountId());
            String content = request.getDeliveryContent();
            String message = request.getMessageTemplate();
            Long version = null;
            if (request.getTemplateId() != null) {
                XianyuFixedDeliveryTemplate template = findOwnedTemplate(
                        request.getXianyuAccountId(), request.getTemplateId());
                if (template == null) return ResultObject.failed(404, "模板不存在或不适用于当前账号");
                content = template.getDeliveryContent();
                message = template.getMessageTemplate();
                version = template.getTemplateVersion();
            }
            content = normalizeRequired(content, "全部发货内容", 200);
            message = buyerMessageService.normalizeDeliveryMessageTemplate(message);
            String rendered = buyerMessageService.renderVariables(message,
                    blankDefault(request.getBuyerName(), "示例会员"),
                    blankDefault(request.getOrderId(), "202607240001"), content);
            if (rendered.length() > 200) return ResultObject.failed(400, "发送预览不能超过200个字符");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("renderedContent", rendered);
            result.put("length", rendered.length());
            result.put("maxLength", 200);
            result.put("templateVersion", version);
            result.put("variables", Map.of("buyerName", blankDefault(request.getBuyerName(), "示例会员"),
                    "orderId", blankDefault(request.getOrderId(), "202607240001")));
            result.put("writePerformed", false);
            return ResultObject.success(result);
        } catch (Exception e) {
            return ResultObject.failed(400, "模板预览失败: " + e.getMessage());
        }
    }

    public ResultObject<List<Map<String, Object>>> references(Long accountId, Long id) {
        requireOwnedAccount(accountId);
        if (findOwnedTemplate(accountId, id) == null) return ResultObject.failed(404, "模板不存在或无权访问");
        return ResultObject.success(templateMapper.findReferences(id));
    }

    public ResultObject<List<Map<String, Object>>> versions(Long accountId, Long id) {
        requireOwnedAccount(accountId);
        if (findOwnedTemplate(accountId, id) == null) return ResultObject.failed(404, "模板不存在或无权访问");
        return ResultObject.success(jdbcTemplate.queryForList("""
                SELECT template_version AS templateVersion,request_id AS requestId,
                       operator_username AS operatorUsername,created_time AS createdTime,
                       CHAR_LENGTH(delivery_content) AS deliveryContentLength,
                       CHAR_LENGTH(message_template) AS messageTemplateLength,account_ids_json AS accountIdsJson
                  FROM xianyu_fixed_delivery_template_version
                 WHERE tenant_id=? AND template_id=? ORDER BY template_version DESC
                """, TenantContext.get(), id));
    }

    public XianyuFixedDeliveryTemplate findOwnedTemplate(Long accountId, Long id) {
        if (accountId == null || id == null) {
            return null;
        }
        return templateMapper.findOwnedById(accountId, id);
    }

    private String normalizeRequired(String value, String fieldName, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private void requireOwnedAccount(Long accountId) {
        if (accountId == null || accountMapper.selectById(accountId) == null) {
            throw new IllegalArgumentException("闲鱼账号不存在或无权访问");
        }
    }

    private List<Long> normalizeAccountIds(List<Long> accountIds, Long legacyAccountId) {
        List<Long> normalized = accountIds == null ? new ArrayList<>()
                : accountIds.stream().filter(Objects::nonNull).distinct().toList();
        if (normalized.isEmpty() && legacyAccountId != null) return List.of(legacyAccountId);
        return normalized;
    }

    private void replaceAccounts(Long templateId, Long tenantId, List<Long> accountIds) {
        sharedAccountLinkMapper.deleteFixedTemplateAccounts(templateId);
        sharedAccountLinkMapper.insertFixedTemplateAccounts(templateId, tenantId, accountIds);
    }

    private XianyuFixedDeliveryTemplate withRelations(XianyuFixedDeliveryTemplate template) {
        template.setXianyuAccountIds(sharedAccountLinkMapper.selectFixedTemplateAccounts(template.getId()));
        template.setReferenceCount(templateMapper.countReferencedConfigs(template.getId()));
        return template;
    }

    private XianyuFixedDeliveryTemplate findReplay(String requestId, Long accountId) {
        List<String> ids = jdbcTemplate.queryForList("""
                SELECT target_id FROM xianyu_operation_log
                 WHERE tenant_id=? AND request_id=? AND operation_module='固定内容模板'
                   AND operation_type IN ('FIXED_TEMPLATE_CREATE','FIXED_TEMPLATE_UPDATE')
                 ORDER BY id DESC LIMIT 1
                """, String.class, TenantContext.get(), requestId);
        if (ids.isEmpty()) return null;
        try { return templateMapper.findOwnedById(accountId, Long.valueOf(ids.getFirst())); }
        catch (Exception ignored) { return null; }
    }

    private void insertVersion(XianyuFixedDeliveryTemplate template, List<Long> accountIds, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_fixed_delivery_template_version
                (tenant_id,template_id,template_version,template_name,delivery_content,message_template,
                 account_ids_json,request_id,operator_user_id,operator_username)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, template.getTenantId(), template.getId(), template.getTemplateVersion(),
                template.getTemplateName(), template.getDeliveryContent(), template.getMessageTemplate(),
                json(accountIds), requestId, UserContext.getUserId(), UserContext.getUsername());
    }

    private void auditSave(XianyuFixedDeliveryTemplate template, Map<String, Object> before,
                           Map<String, Object> after, String requestId, boolean created) {
        XianyuOperationLog audit = auditBase(template,
                created ? "FIXED_TEMPLATE_CREATE" : "FIXED_TEMPLATE_UPDATE",
                created ? "创建固定内容模板" : "更新固定内容模板", requestId);
        audit.setFieldDiffJson(json(Map.of("before", before, "after", after)));
        operationLogService.logRequired(audit);
    }

    private XianyuOperationLog auditBase(XianyuFixedDeliveryTemplate template, String type,
                                          String description, String requestId) {
        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setXianyuAccountId(template.getXianyuAccountId());
        audit.setOperationType(type);
        audit.setOperationModule("固定内容模板");
        audit.setOperationDesc(description);
        audit.setOperationStatus(1);
        audit.setTargetType("FIXED_DELIVERY_TEMPLATE");
        audit.setTargetId(String.valueOf(template.getId()));
        audit.setRequestId(requestId);
        audit.setIdempotencyKey(requestId);
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        return audit;
    }

    private Map<String, Object> auditView(XianyuFixedDeliveryTemplate template, List<Long> accountIds) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("templateName", template.getTemplateName());
        view.put("templateVersion", template.getTemplateVersion());
        view.put("accountIds", accountIds == null ? List.of() : accountIds);
        view.put("deliveryContent", evidence(template.getDeliveryContent()));
        view.put("messageTemplate", evidence(template.getMessageTemplate()));
        return view;
    }

    private Map<String, Object> evidence(String value) {
        String normalized = value == null ? "" : value;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
            return Map.of("length", normalized.length(), "sha256", hash);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成模板内容指纹", e);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法生成模板审计", e); }
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
