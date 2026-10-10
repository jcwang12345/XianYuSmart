package com.xianyusmart.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Closed schema for the published, tenant-specific safe projection. No document/file endpoint. */
public record PdcaProgressSnapshot(Map<String,Object> metadata, List<Map<String,Object>> roles,
        List<Map<String,Object>> items, List<Map<String,Object>> bugs, List<Map<String,Object>> evidenceCatalog) {
    public static final int MAX_BYTES=2*1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> ROLES=Set.of("P","D2","C","A"), OWNERS=Set.of("P","D2","C","A","UNKNOWN");
    private static final Set<String> IMPLEMENTATION=Set.of("UNKNOWN","NOT_IMPLEMENTED","IMPLEMENTED_PENDING_CHECK","HISTORICAL_PENDING_REGRESSION","DEGRADED_PLATFORM","CANCELLED_BY_USER","WAITING_FOR_USER","ACCEPTED");
    private static final Set<String> CHECK=Set.of("NOT_CHECKED","CHECKING","FAILED","PASSED_ISOLATED","PASSED_REAL","WAITING_FOR_EVIDENCE");
    private static final Set<String> SEVERITY=Set.of("P0","P1","P2","P3","UNKNOWN"), KINDS=Set.of("REQUIREMENT","ACCEPTANCE_CHECK","DEFECT","GATE");
    private static final Pattern UNSAFE=Pattern.compile("(?i)(?:https?://|file://|</?[a-z][^>]*>|(?:^|\\s)/(?:Users|Volumes|private|etc|home|tmp)/|[A-Z]:[\\\\/]|\\b(?:cookie|token|password|secret|authorization)\\s*[:=]|bearer\\s+\\S+|eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b|[\\w.+-]+@[\\w.-]+\\.[A-Z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d))");
    private static final Pattern COMMAND=Pattern.compile("(?:^|[\\n`])(?:sudo|curl|wget|ssh|docker|codex)\\s+[A-Za-z0-9/-]");
    public static PdcaProgressSnapshot parse(byte[] bytes,long tenant) {
        try {
            if(bytes.length>MAX_BYTES)throw invalid();
            JsonNode n=JSON.readTree(bytes);fields(n,"schemaVersion tenantId revision generatedAt updatedAt sourceObservedAt lastSuccessfulPublishAt sourceLedgerSha256 sourceRevision coverageDefinition coverageComplete publishedByRole sourceTitles inventory roles items bugs evidenceCatalog");
            if(!n.path("schemaVersion").isInt()||n.path("schemaVersion").intValue()!=1||!n.path("tenantId").isIntegralNumber()||!n.path("tenantId").canConvertToLong()||n.path("tenantId").longValue()!=tenant)throw invalid();
            var meta=new LinkedHashMap<String,Object>();meta.put("schemaVersion",1);meta.put("revision",revision(n.get("revision")));meta.put("sourceRevision",text(n,"sourceRevision",120));
            for(String k:List.of("generatedAt","updatedAt","sourceObservedAt","lastSuccessfulPublishAt"))meta.put(k,date(n,k));
            String hash=hash(n,"sourceLedgerSha256");meta.put("sourceLedgerSha256",hash);
            meta.put("coverageDefinition",text(n,"coverageDefinition",500));if(!n.path("coverageComplete").isBoolean())throw invalid();meta.put("coverageComplete",n.path("coverageComplete").booleanValue());
            meta.put("publishedByRole",enumText(n,"publishedByRole",Set.of("P","D2","C","A","COORD")));
            var roles=new ArrayList<Map<String,Object>>();var seenRoles=new HashSet<String>();
            for(JsonNode r:array(n,"roles",4)) {fields(r,"role responsibility currentBatch state updatedAt sourceRef nextStep blockers");var out=new LinkedHashMap<String,Object>();String role=enumText(r,"role",ROLES);if(!seenRoles.add(role))throw invalid();out.put("role",role);out.put("sourceRef",logical(r,"sourceRef"));
                for(String k:List.of("responsibility","currentBatch","state"))out.put(k,text(r,k,120));out.put("updatedAt",date(r,"updatedAt"));out.put("nextStep",text(r,"nextStep",500));out.put("blockers",strings(r,"blockers",20,500,false));roles.add(Collections.unmodifiableMap(out));}
            if(!seenRoles.equals(ROLES))throw invalid();
            var evidence=new ArrayList<Map<String,Object>>();var evidenceKinds=new HashMap<String,String>();
            for(JsonNode e:array(n,"evidenceCatalog",10000)) {fields(e,"evidenceId safeTitle kind candidateVersion candidateSha safeSummary sourceRef observedAt");var out=new LinkedHashMap<String,Object>();String id=logical(e,"evidenceId");String kind=enumText(e,"kind",Set.of("HISTORICAL","D_DEV","C_ACCEPTANCE","A_RELEASE"));if(evidenceKinds.put(id,kind)!=null)throw invalid();out.put("evidenceId",id);out.put("kind",kind);out.put("safeTitle",text(e,"safeTitle",120));out.put("safeSummary",text(e,"safeSummary",500));out.put("sourceRef",logical(e,"sourceRef"));out.put("observedAt",date(e,"observedAt"));out.put("candidateVersion",nullableText(e,"candidateVersion",120));String sha=nullableText(e,"candidateSha",64);if(sha!=null&&!sha.matches("[a-f0-9]{64}"))throw invalid();out.put("candidateSha",sha);if("C_ACCEPTANCE".equals(kind)&&(sha==null||out.get("candidateVersion")==null||String.valueOf(out.get("candidateVersion")).isBlank()))evidenceKinds.put(id,"C_WITHOUT_CANDIDATE");evidence.add(Collections.unmodifiableMap(out));}
            var sourceTitles=new ArrayList<Map<String,Object>>();var sourceIds=new HashSet<String>();
            for(JsonNode title:array(n,"sourceTitles",2000)){fields(title,"sourceId safeTitle");String id=logical(title,"sourceId");if(!sourceIds.add(id))throw invalid();sourceTitles.add(Map.of("sourceId",id,"safeTitle",text(title,"safeTitle",120)));}
            meta.put("sourceTitles",List.copyOf(sourceTitles));
            JsonNode inventory=n.get("inventory");fields(inventory,"ledgerSourceRecordCount additionalRequirementOrGateCount defectRecordCount independentFeatureDenominator acceptedCurrentCandidateCount");var safeInventory=new LinkedHashMap<String,Object>();
            for(String k:List.of("ledgerSourceRecordCount","additionalRequirementOrGateCount","defectRecordCount","independentFeatureDenominator","acceptedCurrentCandidateCount")){JsonNode v=inventory.get(k);if(v.isNull()){safeInventory.put(k,null);}else{if(!v.isInt()||v.intValue()<0||v.intValue()>10000)throw invalid();safeInventory.put(k,v.intValue());}}
            meta.put("inventory",Collections.unmodifiableMap(safeInventory));
            var allKeys=new HashSet<String>();var items=records(n,"items",false,evidenceKinds,allKeys);var bugs=records(n,"bugs",true,evidenceKinds,allKeys);
            if(((Number)safeInventory.get("ledgerSourceRecordCount")).intValue()+((Number)safeInventory.get("additionalRequirementOrGateCount")).intValue()!=items.size()||((Number)safeInventory.get("defectRecordCount")).intValue()!=bugs.size())throw invalid();
            for(Map<String,Object> row:java.util.stream.Stream.of(roles,items,bugs,evidence).flatMap(Collection::stream).toList())if(!sourceIds.contains(row.get("sourceRef")))throw invalid();
            if(Boolean.TRUE.equals(meta.get("coverageComplete"))&&items.stream().anyMatch(r->"UNKNOWN".equals(r.get("implementationState"))))throw invalid();
            return new PdcaProgressSnapshot(Collections.unmodifiableMap(meta),List.copyOf(roles),items,bugs,List.copyOf(evidence));
        } catch(Exception bad) {throw invalid();}
    }
    private static List<Map<String,Object>> records(JsonNode n,String field,boolean bug,Map<String,String> evidence,Set<String> keys) {
        var result=new ArrayList<Map<String,Object>>();
        for(JsonNode r:array(n,field,10000)) {
            fields(r,bug?"key bugId originalId sourceRef recordKind title severity ownerRole batch devState checkState status blockerSummary evidenceIds nextStep updatedAt":"key requirementId originalId sourceRef recordKind title batch ownerRole implementationState devState checkState releaseState severity blockerType blockerSummary evidenceIds nextStep updatedAt");
            var out=new LinkedHashMap<String,Object>();String id=text(r,bug?"bugId":"requirementId",120),source=logical(r,"sourceRef"),kind=enumText(r,"recordKind",KINDS);JsonNode keyNode=r.get("key");if(!keyNode.isTextual())throw invalid();String key=keyNode.asText();
            if(!key.matches((bug?"bug-":"item-")+"[a-f0-9]{24}")||!keys.add(key)||!keys.add("identity:"+source+"::"+id+"::"+kind)||(bug&&!"DEFECT".equals(kind))||(!bug&&"DEFECT".equals(kind))||!id.equals(text(r,"originalId",120)))throw invalid();
            out.put("key",key);out.put("originalId",id);out.put(bug?"bugId":"requirementId",id);out.put("sourceRef",source);out.put("recordKind",kind);out.put("title",text(r,"title",120));out.put("ownerRole",enumText(r,"ownerRole",OWNERS));out.put("batch",text(r,"batch",120));out.put("severity",enumText(r,"severity",SEVERITY));out.put("devState",text(r,"devState",120));out.put("checkState",enumText(r,"checkState",CHECK));
            if(bug)out.put("status",enumText(r,"status",Set.of("OPEN","FIXED_PENDING_CHECK","CLOSED_HISTORICAL","CLOSED_CURRENT","WAITING")));
            else {out.put("implementationState",enumText(r,"implementationState",IMPLEMENTATION));out.put("releaseState",text(r,"releaseState",120));out.put("blockerType",text(r,"blockerType",120));}
            out.put("blockerSummary",text(r,"blockerSummary",500));out.put("nextStep",text(r,"nextStep",500));out.put("updatedAt",date(r,"updatedAt"));List<String> ids=strings(r,"evidenceIds",50,120,true);if(!evidence.keySet().containsAll(ids))throw invalid();out.put("evidenceIds",ids);
            boolean accepted="ACCEPTED".equals(out.get("implementationState"))||"CLOSED_CURRENT".equals(out.get("status"));
            if(accepted&&(!Set.of("PASSED_ISOLATED","PASSED_REAL").contains(out.get("checkState"))||ids.stream().noneMatch(i->"C_ACCEPTANCE".equals(evidence.get(i)))))throw invalid();
            result.add(Collections.unmodifiableMap(out));
        }return List.copyOf(result);
    }
    static String revision(JsonNode n) {String value=n!=null&&n.isTextual()?n.asText():n!=null&&n.isIntegralNumber()?n.asText():"";if(!value.matches("[1-9][0-9]{0,29}"))throw invalid();return new BigInteger(value).toString();}
    private static JsonNode array(JsonNode n,String key,int max) {JsonNode a=n.get(key);if(a==null||!a.isArray()||a.size()>max)throw invalid();return a;}
    private static void fields(JsonNode n,String allowed) {if(n==null||!n.isObject())throw invalid();Set<String> keys=Set.of(allowed.split(" "));n.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});if(n.size()!=keys.size())throw invalid();}
    private static String text(JsonNode n,String key,int max) {JsonNode v=n.get(key);if(v==null||!v.isTextual())throw invalid();String s=v.asText();if(s.length()>max||UNSAFE.matcher(s).find()||COMMAND.matcher(s).find()||s.codePoints().anyMatch(c->c<32&&c!=10))throw invalid();return s;}
    private static String nullableText(JsonNode n,String key,int max){return n.path(key).isNull()?null:"candidateSha".equals(key)?hash(n,key):text(n,key,max);}
    private static String hash(JsonNode n,String key){JsonNode value=n.get(key);if(value==null||!value.isTextual()||!value.asText().matches("[a-f0-9]{64}"))throw invalid();return value.asText();}
    private static String logical(JsonNode n,String key){JsonNode value=n.get(key);if(value==null||!value.isTextual())throw invalid();String s=value.asText();if(!s.matches("src-[a-f0-9]{16}"))s=text(n,key,120);if(!s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))throw invalid();return s;}
    private static String enumText(JsonNode n,String key,Set<String> allowed){String s=text(n,key,120);if(!allowed.contains(s))throw invalid();return s;}
    private static String date(JsonNode n,String key){String s=text(n,key,50);Instant time=java.time.OffsetDateTime.parse(s).toInstant();if(time.isAfter(Instant.now().plusSeconds(60)))throw invalid();return time.toString();}
    private static List<String> strings(JsonNode n,String key,int max,int length,boolean logical){var out=new ArrayList<String>();for(JsonNode v:array(n,key,max)){if(!v.isTextual())throw invalid();var holder=JSON.createObjectNode().put("v",v.asText());out.add(logical?logical(holder,"v"):text(holder,"v",length));}if(new HashSet<>(out).size()!=out.size())throw invalid();return List.copyOf(out);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("PDCA安全快照无效");}
}
