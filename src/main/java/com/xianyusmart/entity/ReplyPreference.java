package com.xianyusmart.entity;
import lombok.Data;
import java.math.BigDecimal;
@Data
public class ReplyPreference {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonIgnore private Long tenantId;
    private Long xianyuAccountId;
    private String xyGoodsId;
    private Integer welcomeEnabled;
    private String welcomeText;
    private String welcomeImageUrl;
    private BigDecimal bargainFloor;
}
