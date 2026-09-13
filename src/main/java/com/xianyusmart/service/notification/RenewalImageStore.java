package com.xianyusmart.service.notification;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived login images never enter the database, logs or public URLs. */
@Component
public class RenewalImageStore {
    private final Map<String, Entry> images = new ConcurrentHashMap<>();
    public record Entry(Long tenantId, Long accountId, byte[] png, long expiresAt) {}
    public String put(Long tenant, Long account, String dataUrl, long expiresAt) {
        if (tenant == null || account == null || dataUrl == null || !dataUrl.startsWith("data:image/png;base64,"))
            throw new IllegalArgumentException("续期二维码格式无效");
        byte[] png = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        if (png.length == 0 || png.length > 2 * 1024 * 1024) throw new IllegalArgumentException("续期二维码大小无效");
        String reference = UUID.randomUUID().toString();
        images.put(reference, new Entry(tenant, account, label(png,account,expiresAt), expiresAt));
        return reference;
    }
    private byte[] label(byte[] png,Long account,long expiresAt) {
        try {
            var qr=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if(qr==null || qr.getWidth()>1000 || qr.getHeight()>1000)throw new IllegalArgumentException("二维码图片无效");
            var canvas=new java.awt.image.BufferedImage(Math.max(360,qr.getWidth()),qr.getHeight()+72,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=canvas.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());
                g.drawImage(qr,(canvas.getWidth()-qr.getWidth())/2,36,null);
                g.setColor(java.awt.Color.BLACK);g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,16));
                g.drawString("XianYuSmart | Account #"+account,12,24);
                g.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,13));
                String time=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.Instant.ofEpochMilli(expiresAt));
                g.drawString("Scan before "+time+" (UTC+8)",12,canvas.getHeight()-12);
            } finally {g.dispose();}
            var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(canvas,"png",output);return output.toByteArray();
        } catch(java.io.IOException e) {throw new IllegalArgumentException("二维码图片无法读取",e);}
    }
    public Entry get(String reference, Long tenant, Long account) {
        Entry entry = images.get(reference);
        return entry != null && entry.expiresAt() > System.currentTimeMillis()
                && entry.tenantId().equals(tenant) && entry.accountId().equals(account) ? entry : null;
    }
    public void remove(String reference) { if (reference != null) images.remove(reference); }
    @Scheduled(fixedDelay = 60000)
    public void purge() { images.entrySet().removeIf(e -> e.getValue().expiresAt() <= System.currentTimeMillis()); }
}
