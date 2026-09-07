import { request } from '@/utils/request';
import type { ApiResponse } from '@/types';

export interface MediaUploadResult {
  url: string
  mediaType: 'IMAGE' | 'VIDEO'
  storage: 'GOOFISH' | 'LOCAL'
}

/**
 * 素材库通用上传：图片有账号时优先进入闲鱼图床，视频始终保存至本机持久化数据卷。
 */
export function uploadMedia(file: File, accountId?: number): Promise<ApiResponse<MediaUploadResult>> {
  const formData = new FormData()
  formData.append('file', file)
  if (accountId) formData.append('accountId', String(accountId))
  return request({
    url: '/media/upload',
    method: 'POST',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 上传图片到闲鱼CDN
export function uploadImage(accountId: number, file: File): Promise<ApiResponse<string>> {
  const formData = new FormData();
  formData.append('accountId', String(accountId));
  formData.append('file', file);
  
  return request({
    url: '/image/upload',
    method: 'POST',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  });
}

// 通过URL上传图片
export function uploadImageFromUrl(accountId: number, imageUrl: string) {
  return request<string>({
    url: '/image/uploadFromUrl',
    method: 'POST',
    params: { accountId, imageUrl }
  });
}

// 发送图片消息
export function sendImageMessage(data: {
  xianyuAccountId: number;
  cid: string;
  toId: string;
  imageUrl: string;
  width?: number;
  height?: number;
  xyGoodsId?: string;
}) {
  return request<string>({
    url: '/websocket/sendImageMessage',
    method: 'POST',
    data
  });
}
