package com.xianyusmart.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
public class XianyuKeywordReplyRule {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonIgnore
    private Long tenantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long xianyuAccountId;
    private String xyGoodsId;
    private String sharingScope;
    private String keyword;
    private Integer matchMode;
    private Integer isFallback;
    private String createTime;
}
