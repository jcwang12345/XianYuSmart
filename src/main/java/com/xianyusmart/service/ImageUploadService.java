package com.xianyusmart.service;

import com.xianyusmart.common.ResultObject;

/**
 * 图片上传服务接口
 */
public interface ImageUploadService {
    
    /**
     * 上传图片到闲鱼CDN
     *
     * @param accountId 账号ID
     * @param imageData 图片数据（字节数组）
     * @param filename 文件名
     * @return CDN URL
     */
    ResultObject<String> uploadImage(Long accountId, byte[] imageData, String filename);
    
    /**
     * 通过URL上传图片到闲鱼CDN
     *
     * @param accountId 账号ID
     * @param imageUrl 图片URL
     * @return CDN URL
     */
    ResultObject<String> uploadImageFromUrl(Long accountId, String imageUrl);

    /** 将已经保存在本机数据卷的图片同步到闲鱼图片服务。 */
    ResultObject<String> uploadLocalImage(Long accountId, String localMediaUrl);
}
