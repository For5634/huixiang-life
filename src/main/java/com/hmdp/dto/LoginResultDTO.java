package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果DTO，包含token和用户状态信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResultDTO {
    /** 登录token */
    private String token;
    /** 是否为新注册用户（前端据此决定是否弹出用户协议） */
    private Boolean isNewUser;
    /** 是否已同意用户协议 */
    private Boolean agreedPolicy;
}
