package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        //把具体的业务错误信息返回给前端，而不是笼统的"服务器异常"
        String message = e.getMessage();
        if (message != null && !message.isEmpty()) {
            return Result.fail(message);
        }
        return Result.fail("服务器异常");
    }
}
