package com.xianyusmart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.config.rag.DynamicAIChatClientManager;
import com.xianyusmart.config.rag.DynamicVectorStoreManager;
import com.xianyusmart.entity.XianyuSysSetting;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.mapper.XianyuSysSettingMapper;
import com.xianyusmart.service.OperationLogService;
import com.xianyusmart.service.SysSettingService;
import com.google.gson.Gson;
import com.xianyusmart.service.bo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 系统配置服务实现
 * @date 2026/4/22
 */
@Slf4j
@Service
public class SysSettingServiceImpl implements SysSettingService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String MENU_LAYOUT_KEY = "menu_layout";
    private final Gson gson = new Gson();

    /** AI相关配置键，变更时需要触发ChatClient重建 */
    private static final Set<String> AI_RELATED_KEYS = Set.of(
            "ai_provider", "ai_protocol", "ai_custom_name", "ai_api_key", "ai_base_url", "ai_model");

    /** Embedding相关配置键，变更时需要触发VectorStore重建 */
    private static final Set<String> EMBEDDING_RELATED_KEYS = Set.of(
            "ai_embedding_enabled", "ai_embedding_api_key", "ai_embedding_base_url", "ai_embedding_model");

    /** 这些字段通过 API 只能写入，读取只返回 configured/updatedTime。 */
    public static final Set<String> WRITE_ONLY_KEYS = Set.of(
            "ai_api_key", "ai_embedding_api_key", "ai_image_api_key", "email_smtp_password");

    @Autowired
    private XianyuSysSettingMapper sysSettingMapper;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    @Lazy
    private DynamicAIChatClientManager dynamicAIChatClientManager;

    @Autowired
    @Lazy
    private DynamicVectorStoreManager dynamicVectorStoreManager;

    @Override
    public String getSettingValue(String settingKey) {
        if (settingKey == null || settingKey.trim().isEmpty()) {
            return null;
        }

        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, settingKey.trim());
        XianyuSysSetting setting = sysSettingMapper.selectOne(wrapper);

        return setting != null ? setting.getSettingValue() : null;
    }

    @Override
    public GetSettingRespBO getSetting(GetSettingReqBO reqBO) {
        if (reqBO == null || reqBO.getSettingKey() == null || reqBO.getSettingKey().trim().isEmpty()) {
            return null;
        }

        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, reqBO.getSettingKey().trim());
        XianyuSysSetting setting = sysSettingMapper.selectOne(wrapper);

        if (setting == null) {
            return null;
        }

        GetSettingRespBO respBO = new GetSettingRespBO();
        respBO.setSettingKey(setting.getSettingKey());
        populateExternalValue(respBO, setting);
        respBO.setSettingDesc(setting.getSettingDesc());
        respBO.setUpdatedTime(setting.getUpdatedTime());
        return respBO;
    }

    @Override
    public List<GetSettingRespBO> getAllSettings() {
        List<XianyuSysSetting> settings = sysSettingMapper.selectList(null);
        List<GetSettingRespBO> result = new ArrayList<>();

        for (XianyuSysSetting setting : settings) {
            GetSettingRespBO respBO = new GetSettingRespBO();
            respBO.setSettingKey(setting.getSettingKey());
            populateExternalValue(respBO, setting);
            respBO.setSettingDesc(setting.getSettingDesc());
            respBO.setUpdatedTime(setting.getUpdatedTime());
            result.add(respBO);
        }

        return result;
    }

    @Override
    @Transactional
    public GetSettingRespBO saveSetting(SaveSettingReqBO reqBO) {
        if (reqBO == null || reqBO.getSettingKey() == null || reqBO.getSettingKey().trim().isEmpty()) {
            throw new RuntimeException("配置键不能为空");
        }

        String now = LocalDateTime.now().format(FORMATTER);

        // 查询是否已存在
        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, reqBO.getSettingKey().trim());
        XianyuSysSetting existing = sysSettingMapper.selectOne(wrapper);

        String key = reqBO.getSettingKey().trim();
        String incomingValue = reqBO.getSettingValue();
        String beforeAuditValue = auditValue(key, existing == null ? null : existing.getSettingValue());
        if (WRITE_ONLY_KEYS.contains(key) && (incomingValue == null || incomingValue.isBlank())) {
            if (existing == null || existing.getSettingValue() == null || existing.getSettingValue().isBlank()) {
                throw new IllegalArgumentException("密钥不能为空");
            }
            incomingValue = existing.getSettingValue();
        }

        XianyuSysSetting persisted;
        if (existing != null) {
            // 更新
            existing.setSettingValue(incomingValue);
            existing.setSettingDesc(reqBO.getSettingDesc());
            existing.setUpdatedTime(now);
            sysSettingMapper.updateById(existing);
            persisted = existing;
            log.info("[SysSetting] 更新配置成功: key={}", reqBO.getSettingKey());
        } else {
            // 新增
            XianyuSysSetting setting = new XianyuSysSetting();
            setting.setSettingKey(reqBO.getSettingKey().trim());
            setting.setSettingValue(incomingValue);
            setting.setSettingDesc(reqBO.getSettingDesc());
            setting.setCreatedTime(now);
            setting.setUpdatedTime(now);
            sysSettingMapper.insert(setting);
            persisted = setting;
            log.info("[SysSetting] 新增配置成功: key={}", reqBO.getSettingKey());
        }

        String afterAuditValue = auditValue(key, incomingValue);
        writeAudit(key, reqBO.getRequestId(), beforeAuditValue, afterAuditValue);

        // 如果是AI相关配置，触发ChatClient重建
        if (AI_RELATED_KEYS.contains(reqBO.getSettingKey().trim())) {
            log.info("[SysSetting] AI配置变更，触发ChatClient重建: key={}", reqBO.getSettingKey());
            dynamicAIChatClientManager.forceRebuild();
        }
        if (EMBEDDING_RELATED_KEYS.contains(reqBO.getSettingKey().trim())) {
            log.info("[SysSetting] Embedding配置变更，触发VectorStore重建: key={}", reqBO.getSettingKey());
            dynamicVectorStoreManager.forceRebuild();
        }
        GetSettingRespBO response = new GetSettingRespBO();
        response.setSettingKey(key);
        response.setSettingDesc(persisted.getSettingDesc());
        response.setUpdatedTime(persisted.getUpdatedTime());
        populateExternalValue(response, persisted);
        return response;
    }

    private void populateExternalValue(GetSettingRespBO response, XianyuSysSetting setting) {
        boolean writeOnly = WRITE_ONLY_KEYS.contains(setting.getSettingKey());
        response.setConfigured(writeOnly
                ? setting.getSettingValue() != null && !setting.getSettingValue().isBlank()
                : null);
        response.setSettingValue(writeOnly ? null : setting.getSettingValue());
    }

    private String auditValue(String key, String value) {
        if (WRITE_ONLY_KEYS.contains(key)) {
            return value == null || value.isBlank() ? "未配置" : "已配置";
        }
        return value;
    }

    private void writeAudit(String key, String requestId, String beforeValue, String afterValue) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("settingKey", key);
        before.put("settingValue", beforeValue);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("settingKey", key);
        after.put("settingValue", afterValue);
        Map<String, Object> valueDiff = new LinkedHashMap<>();
        valueDiff.put("before", beforeValue);
        valueDiff.put("after", afterValue);

        XianyuOperationLog audit = new XianyuOperationLog();
        audit.setOperationType(MENU_LAYOUT_KEY.equals(key) ? "MENU_LAYOUT_UPDATE" : "SYSTEM_SETTING_UPDATE");
        audit.setOperationModule("系统设置");
        audit.setOperationDesc(MENU_LAYOUT_KEY.equals(key) ? "保存租户菜单布局" : "更新系统配置 " + key);
        audit.setOperationStatus(1);
        audit.setOutcomeState("LOCAL_SUCCESS");
        audit.setDataSource("LOCAL");
        audit.setRequestId(requestId == null || requestId.isBlank()
                ? "setting-" + UUID.randomUUID() : requestId.trim());
        audit.setTargetType("SYS_SETTING");
        audit.setTargetId(key);
        audit.setRequestParams(gson.toJson(before));
        audit.setResponseResult(gson.toJson(after));
        audit.setFieldDiffJson(gson.toJson(Map.of("settingValue", valueDiff)));
        operationLogService.logRequired(audit);
    }

    @Override
    public void deleteSetting(String settingKey) {
        if (settingKey == null || settingKey.trim().isEmpty()) {
            throw new RuntimeException("配置键不能为空");
        }

        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, settingKey.trim());
        sysSettingMapper.delete(wrapper);
        log.info("[SysSetting] 删除配置成功: key={}", settingKey);

        // 如果是AI相关配置，触发ChatClient重建
        if (AI_RELATED_KEYS.contains(settingKey.trim())) {
            log.info("[SysSetting] AI配置删除，触发ChatClient重建: key={}", settingKey);
            dynamicAIChatClientManager.forceRebuild();
        }
        if (EMBEDDING_RELATED_KEYS.contains(settingKey.trim())) {
            log.info("[SysSetting] Embedding配置删除，触发VectorStore重建: key={}", settingKey);
            dynamicVectorStoreManager.forceRebuild();
        }
    }
}
