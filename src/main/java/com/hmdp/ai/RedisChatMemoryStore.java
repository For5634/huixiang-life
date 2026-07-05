package com.hmdp.ai;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的会话记忆存储
 *
 *   - Key: chat:memory:{sessionId}
 *   - Value: JSON 序列化的消息列表
 *   - TTL: 30 分钟（可配置）
 *
 * 支持多轮对话跨请求持久化，会话自动过期清理。
 */
@Slf4j
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:memory:";
    private final StringRedisTemplate stringRedisTemplate;
    private final Duration ttl;

    public RedisChatMemoryStore(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ttl = Duration.ofMinutes(30);
    }

    public RedisChatMemoryStore(StringRedisTemplate stringRedisTemplate, long ttlMinutes) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = KEY_PREFIX + memoryId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        // 续期 TTL（活跃会话不过期）
        stringRedisTemplate.expire(key, ttl);
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = KEY_PREFIX + memoryId;
        String json = ChatMessageSerializer.messagesToJson(messages);
        stringRedisTemplate.opsForValue().set(key, json, ttl);
        log.debug("更新会话记忆: sessionId={}, 消息数={}", memoryId, messages.size());
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = KEY_PREFIX + memoryId;
        stringRedisTemplate.delete(key);
        log.debug("清除会话记忆: sessionId={}", memoryId);
    }
}
