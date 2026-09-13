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
        return put(tenant,account,null,dataUrl,expiresAt);
    }
    public String put(Long tenant, Long account, String accountNote, String dataUrl, long expiresAt) {
        if (tenant == null || account == null || dataUrl == null || !dataUrl.startsWith("data:image/png;base64,"))
            throw new IllegalArgumentException("续期二维码格式无效");
        byte[] png = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        if (png.length == 0 || png.length > 2 * 1024 * 1024) throw new IllegalArgumentException("续期二维码大小无效");
        String reference = UUID.randomUUID().toString();
        images.put(reference, new Entry(tenant, account, label(png,account,accountNote,expiresAt), expiresAt));
        return reference;
    }
    private byte[] label(byte[] png,Long account,String accountNote,long expiresAt) {
        try {
            var qr=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if(qr==null || qr.getWidth()>1000 || qr.getHeight()>1000)throw new IllegalArgumentException("二维码图片无效");
            int width=Math.max(560,qr.getWidth()+32);
            var noteFont=new java.awt.Font("SansSerif",java.awt.Font.PLAIN,22);
            var measure=qr.createGraphics();
            java.util.List<String> lines;
            try { lines=wrapNote(accountNote,measure.getFontMetrics(noteFont),width-48); }
            finally { measure.dispose(); }
            int header=82+lines.size()*30;
            var canvas=new java.awt.image.BufferedImage(width,header+qr.getHeight()+104,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=canvas.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());
                // Keep the original QR pixels and quiet zone untouched; labels live outside it.
                g.drawImage(qr,(width-qr.getWidth())/2,header,null);
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(java.awt.Color.BLACK);g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,26));
                g.drawString("闲鱼扫码续期",24,38);
                g.setFont(new java.awt.Font("SansSerif",java.awt.Font.BOLD,22));
                g.drawString("账号 ID："+account,24,72);
                g.setFont(noteFont);
                for(int i=0;i<lines.size();i++) g.drawString(lines.get(i),24,102+i*30);
                g.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,18));
                String time=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.Instant.ofEpochMilli(expiresAt));
                g.drawString("最晚扫码："+time+"（北京时间）",24,canvas.getHeight()-70);
                g.drawString("平台可能提前失效，请扫最新一张",24,canvas.getHeight()-42);
                g.drawString("请用上述账号确认登录 · 请勿转发",24,canvas.getHeight()-14);
            } finally {g.dispose();}
            var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(canvas,"png",output);return output.toByteArray();
        } catch(java.io.IOException e) {throw new IllegalArgumentException("二维码图片无法读取",e);}
    }
    static java.util.List<String> wrapNote(String note,java.awt.FontMetrics metrics,int maxWidth) {
        String clean=note==null ? "" : note.replaceAll("[\\p{Cc}\\p{Cf}]"," ").strip();
        if(clean.isBlank()) clean="未填写备注";
        // Bound image allocation even if an unexpectedly large value reaches this boundary.
        if(clean.codePointCount(0,clean.length())>512)
            clean=clean.substring(0,clean.offsetByCodePoints(0,512))+"…";
        var lines=new java.util.ArrayList<String>();var line=new StringBuilder("账号备注：");
        for(int cp:clean.codePoints().toArray()) {
            String next=new String(Character.toChars(cp));
            if(metrics.stringWidth(line+next)>maxWidth && !line.isEmpty()) { lines.add(line.toString());line.setLength(0); }
            line.append(next);
        }
        if(!line.isEmpty()) lines.add(line.toString());
        return lines;
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
