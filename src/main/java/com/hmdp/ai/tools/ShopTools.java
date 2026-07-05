package com.hmdp.ai.tools;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能客服工具集（Function Calling）
 *
 * 供 LangChain4j AiServices 调用，实现真实业务操作：
 *   - queryShop: 查询商家信息
 *   - makeReservation: 到店预约（预留）
 */
@Slf4j
@Component
public class ShopTools {

    @Resource
    private IShopService shopService;

    /**
     * 查询商家信息
     *
     * @param keyword 商家名称关键词（支持模糊匹配）
     * @return 商家信息列表（JSON 格式）
     */
    @Tool("根据关键词查询商家信息，返回商家名称、地址、评分、营业时间等")
    public String queryShop(String keyword) {
        log.info("Function Calling: queryShop, keyword={}", keyword);
        try {
            // 查询商家（这里简化为按名称模糊查询，实际可用 Elasticsearch 或 Redis GEO）
            List<Shop> shops = shopService.query()
                    .like("name", keyword)
                    .select("id", "name", "address", "score", "avg_price", "open_hours")
                    .last("limit 10")
                    .list();

            if (shops.isEmpty()) {
                return "未找到相关商家";
            }

            // 格式化返回结果
            List<String> results = shops.stream()
                    .map(s -> String.format("【%s】地址：%s，评分：%.1f，人均：￥%d，营业时间：%s",
                            s.getName(),
                            s.getAddress() != null ? s.getAddress() : "未知",
                            s.getScore() != null ? s.getScore() : 0.0,
                            s.getAvgPrice() != null ? s.getAvgPrice() : 0,
                            s.getOpenHours() != null ? s.getOpenHours() : "未知"))
                    .collect(Collectors.toList());

            return String.join("\n", results);
        } catch (Exception e) {
            log.error("查询商家失败", e);
            return "查询失败，请稍后重试";
        }
    }

    /**
     * 到店预约
     *
     * @param shopId      商家ID
     * @param time        预约时间（如：2026-07-04 18:00）
     * @param personCount 用餐人数
     * @param phone       联系电话
     * @return 预约结果
     */
    @Tool("为用户预约到店服务，需要提供商家ID、预约时间、用餐人数和联系电话")
    public String makeReservation(Long shopId, String time, Integer personCount, String phone) {
        log.info("Function Calling: makeReservation, shopId={}, time={}, count={}, phone={}",
                shopId, time, personCount, phone);
        try {
            // 校验商家是否存在
            Shop shop = shopService.getById(shopId);
            if (shop == null) {
                return "商家不存在，请确认商家ID是否正确";
            }
            // 创建预约记录（实际应写入 tb_reservation 表）
            // 此处简化返回成功信息
            return String.format("预约成功！您已预约【%s】%s，%d位用餐，联系电话%s。请按时到店。",
                    shop.getName(), time,
                    personCount != null ? personCount : 1,
                    phone != null ? phone : "未填写");
        } catch (Exception e) {
            log.error("预约失败", e);
            return "预约失败，请稍后重试";
        }
    }
}
