package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuKeywordReplyRule;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface XianyuKeywordReplyRuleMapper extends BaseMapper<XianyuKeywordReplyRule> {

    @Select("SELECT DISTINCT rule.* FROM xianyu_keyword_reply_rule rule " +
            "JOIN xianyu_keyword_reply_rule_account link ON link.rule_id = rule.id " +
            "WHERE link.xianyu_account_id = #{accountId} " +
            "AND (rule.sharing_scope = 'ACCOUNT' OR rule.xy_goods_id = #{xyGoodsId}) ORDER BY rule.id")
    List<XianyuKeywordReplyRule> selectByAccountAndGoodsId(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId);

    @Select("SELECT * FROM xianyu_keyword_reply_rule WHERE xianyu_account_id = #{accountId} AND xy_goods_id = #{xyGoodsId} AND keyword = #{keyword} AND is_fallback = 0")
    XianyuKeywordReplyRule selectByKeyword(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId, @Param("keyword") String keyword);

    @Select("SELECT rule.* FROM xianyu_keyword_reply_rule rule " +
            "JOIN xianyu_keyword_reply_rule_account link ON link.rule_id = rule.id " +
            "WHERE link.xianyu_account_id = #{accountId} AND (rule.sharing_scope = 'ACCOUNT' OR rule.xy_goods_id = #{xyGoodsId}) " +
            "AND rule.is_fallback = 1 ORDER BY (rule.xy_goods_id = #{xyGoodsId}) DESC, rule.id LIMIT 1")
    XianyuKeywordReplyRule selectFallback(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId);

    @Select("SELECT DISTINCT rule.* FROM xianyu_keyword_reply_rule rule " +
            "JOIN xianyu_keyword_reply_rule_account link ON link.rule_id = rule.id " +
            "WHERE link.xianyu_account_id = #{accountId} AND (rule.sharing_scope = 'ACCOUNT' OR rule.xy_goods_id = #{xyGoodsId}) " +
            "AND rule.match_mode = 2 AND rule.keyword = #{message} AND rule.is_fallback = 0")
    List<XianyuKeywordReplyRule> matchExact(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId, @Param("message") String message);

    @Select("SELECT DISTINCT rule.* FROM xianyu_keyword_reply_rule rule " +
            "JOIN xianyu_keyword_reply_rule_account link ON link.rule_id = rule.id " +
            "WHERE link.xianyu_account_id = #{accountId} AND (rule.sharing_scope = 'ACCOUNT' OR rule.xy_goods_id = #{xyGoodsId}) " +
            "AND rule.match_mode = 1 AND #{message} LIKE CONCAT('%', rule.keyword, '%') AND rule.is_fallback = 0")
    List<XianyuKeywordReplyRule> matchFuzzy(@Param("accountId") Long accountId, @Param("xyGoodsId") String xyGoodsId, @Param("message") String message);

    @Delete("DELETE FROM xianyu_keyword_reply_rule WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
