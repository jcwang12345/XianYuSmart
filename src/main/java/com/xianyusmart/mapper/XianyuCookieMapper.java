package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuCookie;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 闲鱼Cookie Mapper
 */
@Mapper
public interface XianyuCookieMapper extends BaseMapper<XianyuCookie> {
    @Select("SELECT id FROM xianyu_cookie WHERE " +
            "(cookie_text IS NOT NULL AND cookie_text <> '' AND cookie_text NOT LIKE 'enc:v1:%') OR " +
            "(m_h5_tk IS NOT NULL AND m_h5_tk <> '' AND m_h5_tk NOT LIKE 'enc:v1:%') OR " +
            "(websocket_token IS NOT NULL AND websocket_token <> '' AND websocket_token NOT LIKE 'enc:v1:%')")
    List<Long> selectLegacyPlaintextCredentialIds();
}
