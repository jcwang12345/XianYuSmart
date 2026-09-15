package com.xianyusmart.service.kami;

/** 卡密只在受控导出通道返回明文，普通列表仅展示可识别掩码。 */
public final class KamiSecretMasker {
    private KamiSecretMasker() {
    }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) return value;
        int visible = Math.min(4, Math.max(1, value.length() / 4));
        return "••••" + value.substring(value.length() - visible);
    }
}
