package com.tianaiagent.infrastructure.adapter.port.knowledge;

import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeAnswerCache;
import com.tianaiagent.domain.knowledge.adapter.port.KnowledgeIndex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.medical.service.MedicalSafetyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 高风险旁路、知识库版本隔离、结构化约束校验的 RAG 语义缓存。 */
@Service
@Slf4j
public class SemanticRagCache implements KnowledgeAnswerCache {
    private static final String EXACT_PREFIX = "rag:cache:exact:";
    private static final String SEMANTIC_PREFIX = "rag:cache:semantic:";
    private static final Pattern NUMBER_UNIT = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:mg|g|ml|岁|天|周|月|年|次|片|粒|mmhg|%)?", Pattern.CASE_INSENSITIVE);
    private static final List<String> CRITICAL_TERMS = List.of("孕妇", "孕期", "哺乳期", "儿童", "婴儿", "老人", "过敏", "肝功能", "肾功能", "不", "无", "没有", "停药", "剂量", "处方");
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Optional<EmbeddingModel> embeddingModel;
    private final MedicalSafetyService safetyService;
    private final boolean enabled;
    private final double similarityThreshold;
    private final double lexicalThreshold;
    private final int maxEntries;
    private final Duration ttl;

    public SemanticRagCache(StringRedisTemplate redis, ObjectMapper mapper, Optional<EmbeddingModel> embeddingModel,
                            MedicalSafetyService safetyService,
                            @Value("${agent.rag.semantic-cache.enabled:true}") boolean enabled,
                            @Value("${agent.rag.semantic-cache.similarity-threshold:0.985}") double similarityThreshold,
                            @Value("${agent.rag.semantic-cache.lexical-threshold:0.82}") double lexicalThreshold,
                            @Value("${agent.rag.semantic-cache.max-entries:500}") int maxEntries,
                            @Value("${agent.rag.semantic-cache.ttl-hours:12}") long ttlHours) {
        this.redis=redis; this.mapper=mapper; this.embeddingModel=embeddingModel; this.safetyService=safetyService;
        this.enabled=enabled; this.similarityThreshold=similarityThreshold; this.lexicalThreshold=lexicalThreshold;
        this.maxEntries=Math.max(10,maxEntries); this.ttl=Duration.ofHours(Math.max(1,ttlHours));
    }

    public Optional<Hit> get(String spaceId, String question,int topK,String version){
        if(!cacheable(question))return Optional.empty(); String normalized=normalize(question);
        try{
            String exact=redis.opsForValue().get(exactKey(spaceId,normalized,topK,version));
            if(exact!=null)return Optional.of(new Hit(mapper.readValue(exact, KnowledgeIndex.Answer.class), Mode.EXACT));
            if (embeddingModel.isEmpty()) return Optional.empty();
            float[] vector=embeddingModel.get().embed(normalized); Set<String> members=redis.opsForZSet().reverseRange(SEMANTIC_PREFIX + spaceId,0,maxEntries-1);
            if(members==null)return Optional.empty(); CacheEntry best=null; double bestScore=-1;
            for(String member:members){CacheEntry e=mapper.readValue(member,CacheEntry.class);
                if(e.topK()!=topK||!version.equals(e.knowledgeVersion())||!constraints(question).equals(e.constraints())||!intent(question).equals(e.intent()))continue;
                if(jaccard(tokens(normalized),tokens(e.normalizedQuestion()))<lexicalThreshold)continue;
                double score=cosine(vector,e.embedding());if(score>=similarityThreshold&&score>bestScore){best=e;bestScore=score;}}
            return best==null?Optional.empty():Optional.of(new Hit(mapper.readValue(best.resultJson(), KnowledgeIndex.Answer.class), Mode.SEMANTIC));
        }catch(Exception e){log.warn("RAG语义缓存读取失败，降级为完整检索: {}",e.getMessage());return Optional.empty();}
    }

    public void put(String spaceId, String question,int topK,String version,KnowledgeIndex.Answer result){
        if(!cacheable(question)||result==null)return; String normalized=normalize(question);
        try{String json=mapper.writeValueAsString(result);Duration jitter=ttl.plusSeconds(Math.floorMod(normalized.hashCode(),1800));
            redis.opsForValue().set(exactKey(spaceId,normalized,topK,version),json,jitter);
            if (embeddingModel.isEmpty()) return;
            CacheEntry entry=new CacheEntry(normalized,intent(question),constraints(question),topK,version,embeddingModel.get().embed(normalized),json);
            String semanticKey = SEMANTIC_PREFIX + spaceId;
            redis.opsForZSet().add(semanticKey,mapper.writeValueAsString(entry),System.currentTimeMillis());
            Long size=redis.opsForZSet().size(semanticKey);if(size!=null&&size>maxEntries)redis.opsForZSet().removeRange(semanticKey,0,size-maxEntries-1);redis.expire(semanticKey,jitter);
        }catch(Exception e){log.warn("RAG语义缓存写入失败，不影响本次回答: {}",e.getMessage());}
    }

    @Override
    public void evictSpace(String spaceId) {
        if (!enabled) return;
        try (var keys = redis.scan(ScanOptions.scanOptions().match(EXACT_PREFIX + spaceId + ":*").count(200).build())) {
            var batch = new ArrayList<String>(200);
            while (keys.hasNext()) {
                batch.add(keys.next());
                if (batch.size() == 200) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) redis.delete(batch);
        }
        redis.delete(SEMANTIC_PREFIX + spaceId);
    }

    private boolean cacheable(String question){if(!enabled||question==null||question.isBlank()||!"ROUTINE".equals(safetyService.assess(question).level()))return false;String q=question.toLowerCase(Locale.ROOT);return List.of("我","本人","我的","患者","检查值","化验值","诊断","怎么办").stream().noneMatch(q::contains);}
    private static String normalize(String v){return v.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+","");}
    private static String intent(String q){if(any(q,"指南","证据","文献","研究"))return"EVIDENCE";if(any(q,"是什么","定义","解释"))return"DEFINITION";if(any(q,"症状","表现"))return"SYMPTOM";if(any(q,"检查","筛查"))return"EXAM";if(any(q,"预防","风险"))return"PREVENTION";return"GENERAL";}
    private static String constraints(String q){List<String>v=new ArrayList<>();for(String t:CRITICAL_TERMS)if(q.contains(t))v.add(t);Matcher m=NUMBER_UNIT.matcher(q.toLowerCase(Locale.ROOT));while(m.find())v.add(m.group().replaceAll("\\s+",""));Collections.sort(v);return String.join("|",v);}
    private static Set<String> tokens(String t){Set<String>r=new HashSet<>();for(int i=0;i<t.length();i++){if(Character.isLetterOrDigit(t.charAt(i)))r.add(String.valueOf(t.charAt(i)));if(i+1<t.length())r.add(t.substring(i,i+2));}return r;}
    private static double jaccard(Set<String>a,Set<String>b){Set<String>u=new HashSet<>(a);u.addAll(b);Set<String>i=new HashSet<>(a);i.retainAll(b);return u.isEmpty()?0:(double)i.size()/u.size();}
    private static double cosine(float[]a,float[]b){if(a==null||b==null||a.length!=b.length)return 0;double d=0,x=0,y=0;for(int i=0;i<a.length;i++){d+=a[i]*b[i];x+=a[i]*a[i];y+=b[i]*b[i];}return x==0||y==0?0:d/(Math.sqrt(x)*Math.sqrt(y));}
    private static boolean any(String v,String...t){return Arrays.stream(t).anyMatch(v::contains);} private static String exactKey(String spaceId,String q,int k,String v){return EXACT_PREFIX+spaceId+":"+sha256(q+"|"+k+"|"+v);}
    private static String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public record CacheEntry(String normalizedQuestion,String intent,String constraints,int topK,String knowledgeVersion,float[] embedding,String resultJson){}
}
