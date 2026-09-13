package com.xianyusmart.service.reply;
import com.xianyusmart.entity.ReplyPreference;
import com.xianyusmart.event.chatMessageEvent.ChatMessageData;
import com.xianyusmart.mapper.ReplyPreferenceMapper;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.springframework.stereotype.Service;
import java.util.List;
import java.math.BigDecimal;
import java.util.regex.Pattern;

@Service
public class ReplyEnhancementService {
    private final ReplyPreferenceMapper mapper;
    private final XianyuAccountMapper accounts;
    private static final Pattern MONEY=Pattern.compile("(?:[¥￥]\\s*)?(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|人民币)|[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)");
    public ReplyEnhancementService(ReplyPreferenceMapper mapper,XianyuAccountMapper accounts) { this.mapper=mapper;this.accounts=accounts; }
    public ReplyPreference get(Long account,String goods) { requireAccount(account); return mapper.find(account,goods); }
    private com.xianyusmart.entity.XianyuAccount requireAccount(Long id) {
        if(id==null || com.xianyusmart.context.TenantContext.get()==null) throw new IllegalArgumentException("缺少账号或租户信息");
        var account=accounts.selectById(id);
        if(account==null) throw new IllegalArgumentException("账号不存在或无权访问");
        return account;
    }
    public void save(ReplyPreference p) {
        var account=requireAccount(p.getXianyuAccountId());
        if(p.getXyGoodsId()==null || p.getXyGoodsId().isBlank()) throw new IllegalArgumentException("商品不能为空");
        if(p.getWelcomeText()!=null && p.getWelcomeText().length()>600) throw new IllegalArgumentException("欢迎语不能超过600字");
        if(p.getBargainFloor()!=null && (p.getBargainFloor().signum()<0 || p.getBargainFloor().compareTo(new BigDecimal("9999999999.99"))>0))
            throw new IllegalArgumentException("底价无效");
        if(p.getWelcomeImageUrl()!=null && !p.getWelcomeImageUrl().isBlank()) {
            var uri=java.net.URI.create(p.getWelcomeImageUrl());
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || p.getWelcomeImageUrl().length()>2000)
                throw new IllegalArgumentException("欢迎图片必须是有效的 HTTPS 地址");
        }
        if(Integer.valueOf(1).equals(p.getWelcomeEnabled()) && blank(p.getWelcomeText()) && blank(p.getWelcomeImageUrl()))
            throw new IllegalArgumentException("开启首次回复时请填写文字或图片");
        p.setTenantId(account.getTenantId());
        p.setWelcomeEnabled(Integer.valueOf(1).equals(p.getWelcomeEnabled())?1:0);
        mapper.save(p);
    }
    public boolean welcomeEnabled(Long account,String goods) {
        var p=mapper.find(account,goods); return p!=null && Integer.valueOf(1).equals(p.getWelcomeEnabled());
    }
    public ReplyStrategy.ReplyResult welcome(ChatMessageData message) {
        var p=mapper.find(message.getXianyuAccountId(),message.getXyGoodsId());
        if(p==null || !Integer.valueOf(1).equals(p.getWelcomeEnabled()) || blank(message.getSenderUserId())) return null;
        if(blank(p.getWelcomeText()) && blank(p.getWelcomeImageUrl())) return null;
        if(mapper.claim(p.getTenantId(),p.getXianyuAccountId(),p.getXyGoodsId(),message.getSenderUserId())!=1) return null;
        var item=ReplyStrategy.ReplyResult.ReplyItem.textAndImage(p.getWelcomeText(),p.getWelcomeImageUrl(),5);
        return ReplyStrategy.ReplyResult.of(List.of(item));
    }
    public void finishWelcome(ChatMessageData m,boolean success) {
        mapper.finish(m.getXianyuAccountId(),m.getXyGoodsId(),m.getSenderUserId(),success?"SENT":"REVIEW_REQUIRED");
    }
    public String guard(Long account,String goods,String text,boolean priceIntent) {
        var p=mapper.find(account,goods);
        return guardPrice(text,p==null?null:p.getBargainFloor(),priceIntent);
    }
    public static String guardPrice(String text,BigDecimal floor,boolean priceIntent) {
        if(text==null) return null;
        if(text.matches("(?s).*(已经改价|已改价|改好价).*$")) return "价格调整需要卖家确认，请先在闲鱼内与卖家核实。";
        if(priceIntent && floor!=null) {
            var matcher=MONEY.matcher(text);
            while(matcher.find()) {
                var amount=new BigDecimal(matcher.group(1)!=null?matcher.group(1):matcher.group(2));
                if(amount.compareTo(floor)<0) return "这个价格暂时无法接受，具体优惠需要卖家确认。";
            }
        }
        return text;
    }
    private static boolean blank(String v) { return v==null || v.isBlank(); }
}
