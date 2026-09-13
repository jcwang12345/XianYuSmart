package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 标准 TOTP 两步验证和一次性恢复码。 */
@Service
public class TotpService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final SysUserMapper userMapper;
    private final SecureRandom random = new SecureRandom();

    public TotpService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Transactional
    public Map<String, Object> begin(Long userId) {
        SysUser user = requireUser(userId);
        if (Integer.valueOf(1).equals(user.getTotpEnabled())) {
            throw new BusinessException(409, "两步验证已经启用，请先验证并关闭后再重新绑定");
        }
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        String secret = base32Encode(bytes);
        user.setTotpSecret(secret);
        user.setTotpEnabled(0);
        user.setTotpRecoveryCodes(null);
        userMapper.updateById(user);
        String label = "XianYuSmart:" + user.getUsername();
        String uri = "otpauth://totp/" + url(label) + "?secret=" + secret
                + "&issuer=XianYuSmart&algorithm=SHA1&digits=6&period=30";
        return Map.of("secret", secret, "uri", uri, "qrCode", qrDataUrl(uri));
    }

    @Transactional
    public List<String> confirm(Long userId, String code) {
        SysUser user = requireUser(userId);
        if (user.getTotpSecret() == null || !verify(user.getTotpSecret(), code)) {
            throw new BusinessException(400, "两步验证码不正确");
        }
        List<String> recoveryCodes = generateRecoveryCodes();
        user.setTotpEnabled(1);
        user.setTotpRecoveryCodes(recoveryCodes.stream().map(this::normalizeRecoveryCode).map(this::hash)
                .reduce((a, b) -> a + "," + b).orElse(""));
        userMapper.updateById(user);
        return recoveryCodes;
    }

    @Transactional
    public void disable(Long userId, String code) {
        SysUser user = requireUser(userId);
        if (!verifyForUser(user, code)) throw new BusinessException(400, "验证码或恢复码不正确");
        user.setTotpEnabled(0);
        user.setTotpSecret(null);
        user.setTotpRecoveryCodes(null);
        userMapper.updateById(user);
    }

    @Transactional
    public boolean verifyForUser(SysUser user, String code) {
        if (user == null || !Integer.valueOf(1).equals(user.getTotpEnabled())) return true;
        if (verify(user.getTotpSecret(), code)) return true;
        String codeHash = hash(normalizeRecoveryCode(code));
        List<String> hashes = new ArrayList<>(user.getTotpRecoveryCodes() == null || user.getTotpRecoveryCodes().isBlank()
                ? List.of() : List.of(user.getTotpRecoveryCodes().split(",")));
        if (!hashes.remove(codeHash)) return false;
        user.setTotpRecoveryCodes(String.join(",", hashes));
        userMapper.updateById(user);
        return true;
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null || !code.trim().matches("\\d{6}")) return false;
        long step = Instant.now().getEpochSecond() / 30;
        for (long offset = -1; offset <= 1; offset++) {
            if (generate(secret, step + offset).equals(code.trim())) return true;
        }
        return false;
    }

    private String generate(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24) | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8) | (digest[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成两步验证码", e);
        }
    }

    private List<String> generateRecoveryCodes() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byte[] bytes = new byte[5];
            random.nextBytes(bytes);
            String raw = HexFormat.of().formatHex(bytes).toUpperCase();
            values.add(raw.substring(0, 5) + "-" + raw.substring(5));
        }
        return values;
    }

    private String qrDataUrl(String value) {
        try {
            BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("无法生成两步验证二维码", e);
        }
    }

    private SysUser requireUser(Long userId) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getId, userId));
        if (user == null) throw new BusinessException(404, "用户不存在");
        return user;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String normalizeRecoveryCode(String value) {
        return value == null ? "" : value.trim().replace("-", "").toUpperCase();
    }

    private String url(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    static String base32Encode(byte[] input) {
        StringBuilder output = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 31));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) output.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 31));
        return output.toString();
    }

    static byte[] base32Decode(String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (char raw : value.toUpperCase().toCharArray()) {
            int index = ALPHABET.indexOf(raw);
            if (index < 0) continue;
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return output.toByteArray();
    }
}
