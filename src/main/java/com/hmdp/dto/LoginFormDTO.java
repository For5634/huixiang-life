package com.hmdp.dto;

import lombok.Data;

@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
    /** 前端提交的用户协议同意状态 */
    private Boolean agreedPolicy;
}
