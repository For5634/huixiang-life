package com.hmdp.controller;

import com.hmdp.ai.CustomerServiceAssistant;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 智能客服接口
 *
 *   - POST /ai/chat        : 同步对话（兜底）
 *   - GET  /ai/chat/stream : SSE 流式对话（推荐，前端逐字渲染）
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private CustomerServiceAssistant customerServiceAssistant;

    /** 流式调用专用线程池，避免阻塞 Tomcat 工作线程 */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * 智能客服对话（同步）
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");
        String message = request.get("message");

        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        if (message == null || message.isEmpty()) {
            return Result.fail("消息不能为空");
        }

        try {
            log.info("智能客服对话: sessionId={}, message={}", sessionId, message);
            String reply = customerServiceAssistant.chat(sessionId, message);
            return Result.ok(reply);
        } catch (Exception e) {
            log.error("智能客服对话失败", e);
            return Result.fail("AI服务暂时不可用，请稍后重试");
        }
    }

    /**
     * 智能客服对话（流式 SSE）
     *
     * 前端用 EventSource 监听，每收到一个 token 就追加到当前气泡。
     * 完成时发送 [DONE] 标记。
     *
     * 用法：GET /ai/chat/stream?sessionId=xxx&message=yyy
     */
    @GetMapping(value = "/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam("sessionId") String sessionId,
                                 @RequestParam("message") String message) {
        // 超时设 3 分钟（DeepSeek + Function Calling 可能慢）
        SseEmitter emitter = new SseEmitter(180_000L);

        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        if (message == null || message.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("消息不能为空"));
                emitter.complete();
            } catch (IOException ignore) {}
            return emitter;
        }

        final String sid = sessionId;
        final String msg = message;
        streamExecutor.submit(() -> {
            try {
                log.info("智能客服流式对话: sessionId={}, message={}", sid, msg);
                customerServiceAssistant.chatStream(sid, msg)
                        .onNext(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                log.warn("SSE 发送 token 失败：{}", e.getMessage());
                                emitter.completeWithError(e);
                            }
                        })
                        .onComplete(response -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (IOException e) {
                                log.warn("SSE 发送 done 失败：{}", e.getMessage());
                            }
                        })
                        .onError(error -> {
                            try {
                                log.error("智能客服流式错误", error);
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage() == null ? "AI 服务出错" : error.getMessage()));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .start();
            } catch (Exception e) {
                log.error("流式对话启动失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("服务暂时不可用，请稍后重试"));
                    emitter.complete();
                } catch (IOException ignore) {}
            }
        });

        return emitter;
    }
}
