package com.hmdp.ai;

import com.hmdp.ai.tools.ShopTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 智能客服配置
 *
 *   - 模型：DeepSeek V4 Pro（通过 OpenAI 兼容接口调用）
 *   - 会话记忆：基于 Redis 的 ChatMemoryStore，支持多轮对话
 *   - 工具：Function Calling（商家查询、到店预约）
 *
 * 注：不自己定义 openAiChatModel Bean，复用 langchain4j-open-ai-spring-boot-starter
 * 自动装配的 OpenAiChatModel（由 application.yaml 的 langchain4j.open-ai 配置驱动）。
 */
@Slf4j
@Configuration
public class CustomerServiceConfig {

    @Bean
    public RedisChatMemoryStore redisChatMemoryStore(StringRedisTemplate stringRedisTemplate) {
        return new RedisChatMemoryStore(stringRedisTemplate);
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore store) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(store)
                .build();
    }

    /**
     * 智能客服 Assistant（AiServices 构建，自动绑定模型、记忆、工具）
     * openAiChatModel 由 starter 自动装配注入
     */
    @Bean
    public CustomerServiceAssistant customerServiceAssistant(
            OpenAiChatModel openAiChatModel,
            ChatMemoryProvider chatMemoryProvider,
            ShopTools shopTools) {
        return AiServices.builder(CustomerServiceAssistant.class)
                .chatLanguageModel(openAiChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(shopTools)
                .build();
    }
}

