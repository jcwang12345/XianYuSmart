package com.xianyusmart.service.notification;

import com.google.zxing.*;
import com.google.zxing.client.j2se.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class RenewalImageStoreTest {
    @Test void chineseNoteWrapsWithoutTruncatingOrSplittingCodePoints() {
        var image=new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try {
            var metrics=g.getFontMetrics(new Font("SansSerif",Font.PLAIN,22));
            String note="数码配件旗舰店二号店用于区分不同闲鱼账号".repeat(5)+"😀";
            var lines=RenewalImageStore.wrapNote(note,metrics,512);
            assertTrue(lines.size()>1);assertEquals("账号备注："+note,String.join("",lines));
            assertTrue(lines.stream().allMatch(s->metrics.stringWidth(s)<=512));
            assertEquals("账号备注：未填写备注",String.join("",RenewalImageStore.wrapNote(" \n",metrics,512)));
        } finally { g.dispose(); }
    }
    @Test void labelledQrRetainsDecodableOriginalContent() throws Exception {
        String content="XianYuSmart TEST ONLY - not a login credential";
        var matrix=new QRCodeWriter().encode(content,BarcodeFormat.QR_CODE,300,300);
        var source=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(matrix,"PNG",source);
        var store=new RenewalImageStore();
        String ref=store.put(7L,24L,"数码配件旗舰店 · 二号账号", "data:image/png;base64,"+Base64.getEncoder().encodeToString(source.toByteArray()),System.currentTimeMillis()+900000);
        var rendered=ImageIO.read(new ByteArrayInputStream(store.get(ref,7L,24L).png()));
        var result=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(rendered))));
        assertEquals(content,result.getText());assertEquals(560,rendered.getWidth());
        ImageIO.write(rendered,"PNG",new File("target/renewal-qr-preview.png"));
    }
}
