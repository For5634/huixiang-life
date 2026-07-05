package com.hmdp.utils;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsRequest;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 阿里云短信发送工具类
 * 支持开发模式（验证码直接返回）和生产模式（真实短信发送）
 */
@Slf4j
@Component
public class SmsUtils {

    @Value("${aliyun.sms.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.sms.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.sms.sign-name}")
    private String signName;

    @Value("${aliyun.sms.template-code}")
    private String templateCode;

    @Value("${aliyun.sms.region-id}")
    private String regionId;

    @Value("${aliyun.sms.dev-mode:false}")
    private boolean devMode;

    @Value("${aliyun.sms.dev-fixed-code:}")
    private String devFixedCode;

    /**
     * 生成6位纯数字验证码
     * @return 6位数字验证码
     */
    public static String generateCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    /**
     * 发送短信验证码
     * @param phoneNumber 手机号（11 位中国手机号）
     * @param code 业务侧生成的随机验证码
     * @return 开发模式：若配置了 dev-fixed-code，则返回 dev-fixed-code（且调用方应当把 code 也替换成
     *         dev-fixed-code，保证"返回给前端的码 = Redis 中保存的码"）；否则返回随机生成的 code。
     *         生产模式返回 "success"（成功）或 null（失败）
     */
    public String sendSms(String phoneNumber, String code) {
        // 开发模式：不真实发送短信
        if (devMode) {
            String returnCode = (devFixedCode != null && !devFixedCode.isEmpty())
                    ? devFixedCode : code;
            log.info("【开发模式】短信验证码：手机号={}, 验证码={}", phoneNumber, returnCode);
            return returnCode;
        }

        // 生产模式：调用阿里云短信API发送
        try {
            DefaultProfile profile = DefaultProfile.getProfile(regionId, accessKeyId, accessKeySecret);
            IAcsClient client = new DefaultAcsClient(profile);

            SendSmsRequest request = new SendSmsRequest();
            request.setPhoneNumbers(phoneNumber);
            request.setSignName(signName);
            request.setTemplateCode(templateCode);
            request.setTemplateParam("{\"code\":\"" + code + "\"}");

            SendSmsResponse response = client.getAcsResponse(request);

            if ("OK".equals(response.getCode())) {
                log.info("短信发送成功：手机号={}, BizId={}", phoneNumber, response.getBizId());
                return "success";
            } else {
                log.error("短信发送失败：手机号={}, 错误码={}, 错误信息={}",
                        phoneNumber, response.getCode(), response.getMessage());
                return null;
            }
        } catch (ClientException e) {
            log.error("短信发送异常：手机号={}, 错误={}", phoneNumber, e.getErrMsg(), e);
            return null;
        }
    }
}
