package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.SysLoginToken;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前平台用户的多设备会话管理。 */
@Service
public class UserSessionService {

    private final SysLoginTokenMapper tokenMapper;

    public UserSessionService(SysLoginTokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    public List<Map<String, Object>> list() {
        Long userId = requireUserId();
        return tokenMapper.selectList(new LambdaQueryWrapper<SysLoginToken>()
                        .eq(SysLoginToken::getUserId, userId)
                        .orderByDesc(SysLoginToken::getCreatedTime))
                .stream().map(token -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", token.getId());
                    item.put("device", token.getDeviceId());
                    item.put("loginIp", token.getLoginIp());
                    item.put("createdTime", token.getCreatedTime());
                    item.put("expireTime", token.getExpireTime());
                    return item;
                }).toList();
    }

    public void revoke(Long sessionId) {
        Long userId = requireUserId();
        int deleted = tokenMapper.delete(new LambdaQueryWrapper<SysLoginToken>()
                .eq(SysLoginToken::getId, sessionId).eq(SysLoginToken::getUserId, userId));
        if (deleted == 0) throw new BusinessException(404, "会话不存在");
    }

    public void revokeOthers(Long keepSessionId) {
        Long userId = requireUserId();
        LambdaQueryWrapper<SysLoginToken> wrapper = new LambdaQueryWrapper<SysLoginToken>()
                .eq(SysLoginToken::getUserId, userId);
        if (keepSessionId != null) wrapper.ne(SysLoginToken::getId, keepSessionId);
        tokenMapper.delete(wrapper);
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(401, "登录状态已失效");
        return userId;
    }
}
