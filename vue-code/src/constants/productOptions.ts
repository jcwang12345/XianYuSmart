export const MATERIAL_CATEGORY_OPTIONS = [
  { value: '虚拟商品', label: '虚拟商品（通用）' },
  { value: 'Office/WPS插件', label: 'Office / WPS 插件' },
  { value: '软件工具', label: '软件工具' },
  { value: '课程教程', label: '课程 / 视频教程' },
  { value: '数字资料', label: '数字资料 / 模板' },
  { value: '图文素材', label: '图片 / 文案素材' },
  { value: '账号会员', label: '账号 / 会员服务' },
  { value: '设计服务', label: '设计 / 定制服务' },
  { value: '游戏服务', label: '游戏相关服务' },
  { value: '实物商品', label: '实物商品' },
  { value: '其他', label: '其他' }
] as const

export const PRODUCT_TYPE_OPTIONS = [
  { value: 'VIRTUAL', label: '虚拟商品', description: '软件、卡密、数字资料或线上服务' },
  { value: 'PHYSICAL', label: '实物商品', description: '需要快递或当面交付的商品' }
] as const

export const DELIVERY_METHOD_OPTIONS = {
  VIRTUAL: [
    { value: '线上交付', label: '线上交付' },
    { value: '当面交易', label: '当面交易' }
  ],
  PHYSICAL: [
    { value: '快递发货', label: '快递发货' },
    { value: '当面交易', label: '当面交易' }
  ]
} as const
