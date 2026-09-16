package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.security.SensitiveDataCodec;
import com.xianyusmart.cache.CacheService;
import com.google.gson.Gson;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 标准 TOTP 两步验证和一次性恢复码。 */
@Service
public class TotpService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String ATTEMPT_PREFIX = "totp_attempt:";
    private static final int MAX_ATTEMPTS = 5;
    private static final long ATTEMPT_WINDOW_MINUTES = 10;
    private final SysUserMapper userMapper;
    private final CacheService cacheService;
    private final OperationLogService operationLogService;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();

    public TotpService(SysUserMapper userMapper, CacheService cacheService,
                       OperationLogService operationLogService) {
        this.userMapper = userMapper;
        this.cacheService = cacheService;
        this.operationLogService = operationLogService;
    }

    @Transactional
    public Map<String, Object> begin(Long userId, String requestId) {
        SysUser user = requireUser(userId);
        if (Integer.valueOf(1).equals(user.getTotpEnabled())) {
            throw new BusinessException(409, "两步验证已经启用，请先验证并关闭后再重新绑定");
        }
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        String secret = base32Encode(bytes);
        user.setTotpSecret(SensitiveDataCodec.encrypt(secret));
        user.setTotpEnabled(0);
        user.setTotpRecoveryCodes(null);
        userMapper.updateById(user);
        audit(user, "TWO_FACTOR_ENROLLMENT_BEGIN", requestId,
                Map.of("enrollment", "NOT_STARTED"), Map.of("enrollment", "PENDING"));
        String label = "XianYuSmart:" + user.getUsername();
        String uri = "otpauth://totp/" + url(label) + "?secret=" + secret
                + "&issuer=XianYuSmart&algorithm=SHA1&digits=6&period=30";
        return Map.of("secret", secret, "uri", uri, "qrCode", qrDataUrl(uri));
    }

    @Transactional
    public List<String> confirm(Long userId, String code, String requestId) {
        SysUser user = requireUser(userId);
        assertAttemptAllowed(userId);
        if (user.getTotpSecret() == null || !verify(SensitiveDataCodec.decrypt(user.getTotpSecret()), code)) {
            recordFailure(userId);
            throw new BusinessException(400, "两步验证码不正确");
        }
        List<String> recoveryCodes = generateRecoveryCodes();
        user.setTotpEnabled(1);
        user.setTotpRecoveryCodes(recoveryCodes.stream().map(this::normalizeRecoveryCode).map(this::hash)
                .reduce((a, b) -> a + "," + b).orElse(""));
        userMapper.updateById(user);
        clearFailures(userId);
        audit(user, "TWO_FACTOR_ENABLED", requestId,
                Map.of("enabled", false, "recoveryCodeCount", 0),
                Map.of("enabled", true, "recoveryCodeCount", recoveryCodes.size()));
        return recoveryCodes;
    }

    @Transactional
    public void disable(Long userId, String code, String requestId) {
        SysUser user = requireUser(userId);
        int recoveryCount = recoveryCodeCount(user);
        if (!verifyForUser(user, code)) throw new BusinessException(400, "验证码或恢复码不正确");
        user.setTotpEnabled(0);
        user.setTotpSecret(null);
        user.setTotpRecoveryCodes(null);
        userMapper.updateById(user);
        audit(user, "TWO_FACTOR_DISABLED", requestId,
                Map.of("enabled", true, "recoveryCodeCount", recoveryCount),
                Map.of("enabled", false, "recoveryCodeCount", 0));
    }

    @Transactional
    public boolean verifyForUser(SysUser user, String code) {
        return verifyForUserInternal(user, code) == LoginVerificationStatus.SUCCESS;
    }

    /** 登录专用校验结果，区分错误验证码与已触发的用户级限速。 */
    @Transactional
    public LoginVerificationStatus verifyForLogin(SysUser user, String code) {
        return verifyForUserInternal(user, code);
    }

    private LoginVerificationStatus verifyForUserInternal(SysUser user, String code) {
        if (user == null || !Integer.valueOf(1).equals(user.getTotpEnabled())) {
            return LoginVerificationStatus.SUCCESS;
        }
        if (!attemptAllowed(user.getId())) return LoginVerificationStatus.RATE_LIMITED;
        if (verify(SensitiveDataCodec.decrypt(user.getTotpSecret()), code)) {
            clearFailures(user.getId());
            return LoginVerificationStatus.SUCCESS;
        }
        String codeHash = hash(normalizeRecoveryCode(code));
        String recoveryCodesSnapshot = user.getTotpRecoveryCodes();
        List<String> hashes = new ArrayList<>(recoveryCodesSnapshot == null || recoveryCodesSnapshot.isBlank()
                ? List.of() : List.of(recoveryCodesSnapshot.split(",")));
        if (!hashes.remove(codeHash)) {
            recordFailure(user.getId());
            return attemptAllowed(user.getId())
                    ? LoginVerificationStatus.INVALID : LoginVerificationStatus.RATE_LIMITED;
        }
        String remainingCodes = String.join(",", hashes);
        if (userMapper.consumeRecoveryCodesIfUnchanged(
                user.getId(), recoveryCodesSnapshot, remainingCodes) != 1) {
            recordFailure(user.getId());
            return attemptAllowed(user.getId())
                    ? LoginVerificationStatus.INVALID : LoginVerificationStatus.RATE_LIMITED;
        }
        user.setTotpRecoveryCodes(remainingCodes);
        clearFailures(user.getId());
        return LoginVerificationStatus.SUCCESS;
    }

    public enum LoginVerificationStatus {
        SUCCESS,
        INVALID,
        RATE_LIMITED
    }

    public int recoveryCodeCount(SysUser user) {
        if (user == null || user.getTotpRecoveryCodes() == null || user.getTotpRecoveryCodes().isBlank()) return 0;
        return (int) List.of(user.getTotpRecoveryCodes().split(",")).stream().filter(value -> !value.isBlank()).count();
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

    private void assertAttemptAllowed(Long userId) {
        if (!attemptAllowed(userId)) {
            throw new BusinessException(429, "两步验证码错误次数过多，请10分钟后再试");
        }
    }

    private boolean attemptAllowed(Long userId) {
        Object value = cacheService.get(ATTEMPT_PREFIX + userId);
        if (value == null) return true;
        try {
            return Long.parseLong(value.toString()) < MAX_ATTEMPTS;
        } catch (Exception ignored) {
            return true;
        }
    }

    private void recordFailure(Long userId) {
        String key = ATTEMPT_PREFIX + userId;
        boolean existed = cacheService.containsKey(key);
        cacheService.increment(key);
        if (!existed || cacheService.getExpire(key) == -1) {
            cacheService.expire(key, ATTEMPT_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
    }

    private void clearFailures(Long userId) {
        cacheService.remove(ATTEMPT_PREFIX + userId);
    }

    private void audit(SysUser user, String operationType, String requestId,
                       Map<String, Object> before, Map<String, Object> after) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setOperationType(operationType);
        log.setOperationModule("账号安全");
        log.setOperationDesc("更新两步验证安全状态");
        log.setOperationStatus(1);
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL");
        log.setRequestId(requestId == null || requestId.isBlank()
                ? "two-factor-" + UUID.randomUUID() : requestId.trim());
        log.setTargetType("SYS_USER");
        log.setTargetId(String.valueOf(user.getId()));
        log.setRequestParams(gson.toJson(before));
        log.setResponseResult(gson.toJson(after));
        log.setFieldDiffJson(gson.toJson(Map.of("before", before, "after", after)));
        operationLogService.logRequired(log);
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
