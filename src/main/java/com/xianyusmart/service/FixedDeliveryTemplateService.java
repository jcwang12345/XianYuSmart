package com.xianyusmart.service;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.FixedDeliveryTemplateReqDTO;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuFixedDeliveryTemplateMapper;
import com.xianyusmart.mapper.SharedAccountLinkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * 固定内容模板管理
 */
@Service
public class FixedDeliveryTemplateService {

    private final XianyuFixedDeliveryTemplateMapper templateMapper;
    private final XianyuAccountMapper accountMapper;
    private final BuyerMessageService buyerMessageService;
    private final SharedAccountLinkMapper sharedAccountLinkMapper;

    public FixedDeliveryTemplateService(XianyuFixedDeliveryTemplateMapper templateMapper,
                                        XianyuAccountMapper accountMapper,
                                        BuyerMessageService buyerMessageService,
                                        SharedAccountLinkMapper sharedAccountLinkMapper) {
        this.templateMapper = templateMapper;
        this.accountMapper = accountMapper;
        this.buyerMessageService = buyerMessageService;
        this.sharedAccountLinkMapper = sharedAccountLinkMapper;
    }

    @Transactional
    public ResultObject<XianyuFixedDeliveryTemplate> save(FixedDeliveryTemplateReqDTO request) {
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
            // 发货凭证接口最多接收200个字符，保存前校验最终内容避免履约时才失败。
            if (renderedPreview.length() > 200) {
                throw new IllegalArgumentException("发送预览不能超过200个字符");
            }

            XianyuFixedDeliveryTemplate template;
            if (request.getId() == null) {
                template = new XianyuFixedDeliveryTemplate();
                template.setXianyuAccountId(primaryAccountId);
                template.setTenantId(accountMapper.selectById(primaryAccountId).getTenantId());
            } else {
                template = templateMapper.selectById(request.getId());
                if (template == null) {
                    return ResultObject.failed("固定内容模板不存在");
                }
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
            return ResultObject.success(template);
        } catch (Exception e) {
            return ResultObject.failed("保存固定内容模板失败: " + e.getMessage());
        }
    }

    public ResultObject<List<XianyuFixedDeliveryTemplate>> list(Long accountId) {
        try {
            requireOwnedAccount(accountId);
            List<XianyuFixedDeliveryTemplate> templates = templateMapper.findByAccountId(accountId);
            templates.forEach(template -> template.setXianyuAccountIds(
                    sharedAccountLinkMapper.selectFixedTemplateAccounts(template.getId())));
            return ResultObject.success(templates);
        } catch (Exception e) {
            return ResultObject.failed(e.getMessage());
        }
    }

    @Transactional
    public ResultObject<Void> delete(Long accountId, Long id) {
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
        templateMapper.deleteById(id);
        return ResultObject.success(null);
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
}
