import { request } from '@/utils/request';

export interface KeywordReplyContent {
  id: string | number;
  ruleId: string | number;
  replyText: string;
  replyImageUrl: string;
  versionNo?: number;
  status?: string;
  effectiveTime?: string;
  expiresTime?: string;
}

export interface KeywordReplyRule {
  id: string | number;
  xianyuAccountId: string | number;
  xianyuAccountIds: number[];
  sharingScope: 'GOODS' | 'ACCOUNT';
  xyGoodsId: string;
  keyword: string;
  matchMode: number;
  matchType?: 'EXACT' | 'CONTAINS' | 'REGEX';
  priority?: number;
  enabled?: boolean | number;
  versionNo?: number;
  effectiveTime?: string;
  expiresTime?: string;
  isFallback: number;
  contents: KeywordReplyContent[];
}

export interface KeywordRuleVersion {
  id: number;
  ruleId: number;
  versionNo: number;
  keyword: string;
  matchType: 'EXACT' | 'CONTAINS' | 'REGEX';
  priority: number;
  enabled: boolean | number;
  isFallback: boolean | number;
  sharingScope: 'GOODS' | 'ACCOUNT';
  accountIds: number[];
  contents: Array<{ replyText?: string; replyImageUrl?: string }>;
  effectiveTime: string;
  expiresTime?: string;
  requestId: string;
  createdUsername?: string;
  createdTime: string;
}

export interface SaveKeywordRuleVersionCommand {
  keyword: string;
  matchType: 'EXACT' | 'CONTAINS' | 'REGEX';
  priority: number;
  enabled: boolean;
  effectiveTime?: string;
  expiresTime?: string;
  accountIds: number[];
  contents: Array<{ replyText?: string; replyImageUrl?: string }>;
  requestId: string;
}

export function getKeywordReplyRules(data: { xianyuAccountId: number; xyGoodsId: string }) {
  return request<KeywordReplyRule[]>({ url: '/keyword-reply/rules', method: 'POST', data });
}

export function addKeywordRule(data: { xianyuAccountId: number; xyGoodsId: string; keyword: string }) {
  return request<KeywordReplyRule>({ url: '/keyword-reply/addRule', method: 'POST', data });
}

export function deleteKeywordRule(data: { ruleId: string | number }) {
  return request({ url: '/keyword-reply/deleteRule', method: 'POST', data });
}

export function updateKeyword(data: { ruleId: string | number; keyword: string }) {
  return request({ url: '/keyword-reply/updateKeyword', method: 'POST', data });
}

export function updateKeywordRuleMatchMode(data: { ruleId: string | number; matchMode: number }) {
  return request({ url: '/keyword-reply/updateMatchMode', method: 'POST', data });
}

export function updateKeywordRuleAccounts(data: { ruleId: string | number; xianyuAccountIds: number[] }) {
  return request({ url: '/keyword-reply/updateAccounts', method: 'POST', data });
}

export function ensureFallbackRule(data: { xianyuAccountId: number; xyGoodsId: string }) {
  return request<KeywordReplyRule>({ url: '/keyword-reply/ensureFallbackRule', method: 'POST', data });
}

export function addKeywordContent(data: { ruleId: string | number; replyText?: string; replyImageUrl?: string }) {
  return request<KeywordReplyContent>({ url: '/keyword-reply/addContent', method: 'POST', data });
}

export function updateKeywordContent(data: { contentId: string | number; replyText?: string; replyImageUrl?: string }) {
  return request({ url: '/keyword-reply/updateContent', method: 'POST', data });
}

export function deleteKeywordContent(data: { contentId: string | number }) {
  return request({ url: '/keyword-reply/deleteContent', method: 'POST', data });
}

export function saveKeywordRuleVersion(ruleId: string | number, data: SaveKeywordRuleVersionCommand) {
  return request<KeywordRuleVersion>({ url: `/keyword-reply/rules/${ruleId}`, method: 'PUT', data });
}

export function getKeywordRuleVersions(ruleId: string | number) {
  return request<{ ruleId: number; records: KeywordRuleVersion[]; dataNotice: string }>({
    url: `/keyword-reply/rules/${ruleId}/versions`, method: 'GET'
  });
}
