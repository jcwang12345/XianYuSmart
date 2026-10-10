package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Read-only published files; no repository/document fallbacks and no business writes. */
@Service
public class PdcaProgressService {
    private final SysUserMapper users;private final PlatformPermissionService permissions;
    private final String root;private final Set<Long> tenants;private final long staleSeconds;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public PdcaProgressService(SysUserMapper users,PlatformPermissionService permissions,
            @Value("${app.pdca.progress.root:}") String root,
            @Value("${app.pdca.progress.tenants:}") String tenantMap,
            @Value("${app.pdca.progress.stale-seconds:1800}") long staleSeconds) {
        this.users=users;this.permissions=permissions;this.root=root;this.staleSeconds=Math.max(60,staleSeconds);
        var allowed=new HashSet<Long>();if(tenantMap!=null&&!tenantMap.isBlank())for(String id:tenantMap.split(",")){if(!id.trim().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("PDCA租户配置无效");allowed.add(Long.parseLong(id.trim()));}this.tenants=Set.copyOf(allowed);
    }
    private long authorize() {
        Long tenant=TenantContext.get(),id=UserContext.getUserId();
        if(tenant==null||id==null)throw new BusinessException(401,"请登录后读取PDCA进度");
        SysUser user=users.selectById(id);
        if(!Objects.equals(UserContext.getTenantId(),tenant)||user==null||!Objects.equals(user.getId(),id)||!Objects.equals(user.getTenantId(),tenant)||!Integer.valueOf(1).equals(user.getStatus())
                ||!(SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole())||"OWNER".equals(user.getMemberRole())||"TENANT_ADMIN".equals(user.getMemberRole()))
                ||!permissions.hasPermission(user,PermissionCatalog.MENU_SETTINGS))throw new BusinessException(403,"当前身份无权读取PDCA进度");
        return tenant;
    }
    public Map<String,Object> overview(String knownRevision) {
        validateRevision(knownRevision);long tenant=authorize();PdcaProgressSnapshot snapshot=load(tenant);
        if(snapshot==null)return unavailable(tenant);
        var result=metadata(snapshot);result.put("roles",snapshot.roles());var counts=new LinkedHashMap<String,Object>();
        counts.put("registeredItems",snapshot.items().size());counts.put("registeredBugs",snapshot.bugs().size());counts.put("recordKinds",count(snapshot.items(),"recordKind"));counts.put("implementation",count(snapshot.items(),"implementationState"));counts.put("check",count(snapshot.items(),"checkState"));counts.put("bugStatus",count(snapshot.bugs(),"status"));counts.put("bugSeverity",count(snapshot.bugs(),"severity"));result.put("counts",counts);
        result.put("filterOptions",Map.of("batches",java.util.stream.Stream.concat(snapshot.items().stream(),snapshot.bugs().stream()).map(r->r.get("batch")).distinct().sorted(Comparator.comparing(Object::toString)).toList()));result.put("revisionChanged",knownRevision!=null&&!knownRevision.equals(snapshot.metadata().get("revision")));return result;
    }
    public Map<String,Object> items(Map<String,String> query) {
        Set<String> allowed=Set.of("type","ownerRole","batch","state","severity","keyword","page","pageSize","knownRevision");if(!allowed.containsAll(query.keySet()))throw new BusinessException(400,"PDCA查询参数无效");
        String type=query.getOrDefault("type","requirement");if(!Set.of("requirement","bug").contains(type))throw new BusinessException(400,"PDCA记录类型无效");
        int page=integer(query.getOrDefault("page","1"),1,100000),size=integer(query.getOrDefault("pageSize","20"),1,50);
        String owner=query.get("ownerRole"),severity=query.get("severity"),state=query.get("state"),batch=query.get("batch"),keyword=query.getOrDefault("keyword","");
        if(owner!=null&&!Set.of("P","D2","C","A","UNKNOWN").contains(owner)||severity!=null&&!Set.of("P0","P1","P2","P3","UNKNOWN").contains(severity))throw new BusinessException(400,"PDCA筛选无效");
        if(keyword.length()>100||keyword.codePoints().anyMatch(c->c<32)||batch!=null&&batch.length()>120||state!=null&&state.length()>120)throw new BusinessException(400,"PDCA筛选过长");validateRevision(query.get("knownRevision"));
        long tenant=authorize();PdcaProgressSnapshot snapshot=load(tenant);
        if(snapshot==null){var none=unavailable(tenant);none.put("records",List.of());none.put("total",0);none.put("page",1);none.put("pageSize",size);return none;}
        boolean changed=query.containsKey("knownRevision")&&!query.get("knownRevision").equals(snapshot.metadata().get("revision"));if(changed)page=1;
        String search=keyword.toLowerCase(Locale.ROOT);List<Map<String,Object>> source=type.equals("bug")?snapshot.bugs():snapshot.items();var filtered=source.stream().filter(r->owner==null||owner.equals(r.get("ownerRole"))).filter(r->severity==null||severity.equals(r.get("severity"))).filter(r->batch==null||batch.equals(r.get("batch"))).filter(r->state==null||state.equals(r.get(type.equals("bug")?"status":"implementationState"))).filter(r->search.isEmpty()||List.of("title","key","originalId","requirementId","bugId","blockerSummary","nextStep").stream().anyMatch(k->String.valueOf(r.get(k)).toLowerCase(Locale.ROOT).contains(search))).toList();
        int start=Math.min(filtered.size(),(page-1)*size),end=Math.min(filtered.size(),start+size);var records=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> row:filtered.subList(start,end)){var projection=new LinkedHashMap<>(row);projection.put("evidence",snapshot.evidenceCatalog().stream().filter(e->((List<?>)row.get("evidenceIds")).contains(e.get("evidenceId"))).toList());records.add(projection);}
        var result=metadata(snapshot);result.put("records",records);result.put("total",filtered.size());result.put("page",page);result.put("pageSize",size);result.put("revisionChanged",changed);if(snapshot.items().isEmpty()&&snapshot.bugs().isEmpty())result.put("reason","EMPTY_VERIFIED");return result;
    }
    private Map<String,Object> metadata(PdcaProgressSnapshot snapshot) {var result=new LinkedHashMap<>(snapshot.metadata());boolean stale=Instant.parse((String)snapshot.metadata().get("sourceObservedAt")).plusSeconds(staleSeconds).isBefore(Instant.now());result.put("snapshotAvailable",true);result.put("stale",stale);result.put("staleReason",stale?"SOURCE_OBSERVATION_EXPIRED":null);result.put("sourceHealth",stale?"STALE":"OK");return result;}
    private Map<String,Object> unavailable(long tenant){var result=new LinkedHashMap<String,Object>();result.put("snapshotAvailable",false);result.put("reason",root==null||root.isBlank()?"NOT_CONFIGURED":"NO_LEDGER_FOR_TENANT");result.put("revision",null);result.put("coverageComplete",false);result.put("sourceHealth","NOT_INITIALIZED");result.put("stale",false);result.put("roles",List.of());return result;}
    private Map<String,Integer> count(List<Map<String,Object>> records,String key){var counts=new TreeMap<String,Integer>();records.forEach(r->counts.merge(String.valueOf(r.get(key)),1,Integer::sum));return counts;}
    private PdcaProgressSnapshot load(long tenant) {
        if(root==null||root.isBlank()||!tenants.contains(tenant))return null;
        try {Path dir=Path.of(root).toAbsolutePath().normalize();if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return null;safePath(dir);String prefix="pdca-tenant-"+tenant;
            Path manifestPath=dir.resolve(prefix+"-manifest.json");if(!Files.exists(manifestPath,LinkOption.NOFOLLOW_LINKS))return null;
            for(int attempt=0;attempt<3;attempt++) {
                byte[] bytes=read(manifestPath,4096);JsonNode manifest=json.readTree(bytes);Set<String> keys=Set.of("schemaVersion","tenantId","revision","snapshotFile","snapshotSha256","sourceLedgerSha256","previousRevision","previousSnapshotFile","previousSnapshotSha256");if(!manifest.isObject()||manifest.size()!=keys.size())throw new IllegalArgumentException();manifest.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw new IllegalArgumentException();});
                if(!manifest.path("schemaVersion").isInt()||manifest.path("schemaVersion").intValue()!=1||!manifest.path("tenantId").isIntegralNumber()||!manifest.path("tenantId").canConvertToLong())throw new IllegalArgumentException();
                if(manifest.path("tenantId").longValue()!=tenant)throw new BusinessException(403,"PDCA来源不属于当前租户");
                String revision=PdcaProgressSnapshot.revision(manifest.get("revision")),name=manifest.path("snapshotFile").asText(),hash=manifest.path("snapshotSha256").asText(),ledger=manifest.path("sourceLedgerSha256").asText();
                if(!name.equals(prefix+"-r"+revision+".json")||!hash.matches("[a-f0-9]{64}")||!ledger.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
                JsonNode previous=manifest.get("previousRevision");if(previous.isNull()){if(!manifest.get("previousSnapshotFile").isNull()||!manifest.get("previousSnapshotSha256").isNull())throw new IllegalArgumentException();}else{String prior=PdcaProgressSnapshot.revision(previous);if(new java.math.BigInteger(prior).compareTo(new java.math.BigInteger(revision))>=0||!manifest.path("previousSnapshotFile").isTextual()||!manifest.path("previousSnapshotFile").asText().equals(prefix+"-r"+prior+".json")||!manifest.path("previousSnapshotSha256").isTextual()||!manifest.path("previousSnapshotSha256").asText().matches("[a-f0-9]{64}"))throw new IllegalArgumentException();}
                byte[] payload=read(dir.resolve(name),PdcaProgressSnapshot.MAX_BYTES);if(!MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IllegalArgumentException();
                if(!Arrays.equals(bytes,read(manifestPath,4096)))continue;
                JsonNode payloadRoot=json.readTree(payload);if(payloadRoot.path("tenantId").isIntegralNumber()&&payloadRoot.path("tenantId").canConvertToLong()&&payloadRoot.path("tenantId").longValue()!=tenant)throw new BusinessException(403,"PDCA来源不属于当前租户");
                PdcaProgressSnapshot snapshot=PdcaProgressSnapshot.parse(payload,tenant);if(!revision.equals(snapshot.metadata().get("revision"))||!ledger.equals(snapshot.metadata().get("sourceLedgerSha256")))throw new IllegalArgumentException();return snapshot;
            }throw new IllegalArgumentException();
        }catch(BusinessException rejected){throw rejected;}catch(Exception failure){throw new BusinessException(503,"PDCA进度更新不可用，请稍后重试；请求标识 "+UUID.randomUUID());}
    }
    static void safePath(Path path)throws java.io.IOException {Path current=path.getRoot();for(Path part:path){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new java.io.IOException("Unsafe source");}if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("Invalid source");}
    private byte[] read(Path path,int limit)throws java.io.IOException {safePath(path.getParent());if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>limit)throw new java.io.IOException("Invalid source");try(SeekableByteChannel channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){if(channel.size()>limit)throw new java.io.IOException("Invalid source");var output=new java.io.ByteArrayOutputStream();ByteBuffer buffer=ByteBuffer.allocate(8192);while(channel.read(buffer)>0){buffer.flip();output.write(buffer.array(),0,buffer.remaining());if(output.size()>limit)throw new java.io.IOException("Invalid source");buffer.clear();}return output.toByteArray();}}
    private int integer(String s,int min,int max){try{if(!s.matches("[0-9]{1,6}"))throw new IllegalArgumentException();int value=Integer.parseInt(s);if(value<min||value>max)throw new IllegalArgumentException();return value;}catch(Exception bad){throw new BusinessException(400,"PDCA分页参数无效");}}
    private void validateRevision(String value){if(value!=null&&!value.matches("[1-9][0-9]{0,29}"))throw new BusinessException(400,"PDCA版本参数无效");}
}
