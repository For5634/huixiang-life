package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 系统常量
 *
 * 图片上传目录可通过 application.yaml 的 huixiang.upload.image-dir 配置，
 * 默认指向当前项目 nginx 静态资源目录。
 */
@Component
public class SystemConstants {

    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    private static String imageUploadDir;

    @Value("${huixiang.upload.image-dir:./nginx-1.18.0/html/hmdp/imgs}")
    public void setImageUploadDir(String dir) {
        imageUploadDir = dir;
    }

    public static String getImageUploadDir() {
        return imageUploadDir;
    }
}

