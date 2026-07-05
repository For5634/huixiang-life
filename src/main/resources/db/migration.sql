-- ============================================================
-- 惠享生活平台 - 数据库迁移脚本（安全版）
-- 使用存储过程检查字段/索引是否存在，避免重复执行报错
-- ============================================================

USE `hmdp`;

-- 创建存储过程：安全添加字段
DROP PROCEDURE IF EXISTS `add_column_if_not_exists`;
DELIMITER //
CREATE PROCEDURE `add_column_if_not_exists`(
    IN table_name VARCHAR(64),
    IN column_name VARCHAR(64),
    IN column_definition VARCHAR(255),
    IN after_column VARCHAR(64)
)
BEGIN
    DECLARE column_exists INT DEFAULT 0;

    SELECT COUNT(*) INTO column_exists
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = table_name
      AND column_name = column_name;

    IF column_exists = 0 THEN
        SET @sql = CONCAT('ALTER TABLE `', table_name, '` ADD COLUMN `', column_name, '` ', column_definition);
        IF after_column IS NOT NULL THEN
            SET @sql = CONCAT(@sql, ' AFTER `', after_column, '`');
        END IF;
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- 创建存储过程：安全添加索引
DROP PROCEDURE IF EXISTS `add_index_if_not_exists`;
DELIMITER //
CREATE PROCEDURE `add_index_if_not_exists`(
    IN table_name VARCHAR(64),
    IN index_name VARCHAR(64),
    IN index_columns VARCHAR(255)
)
BEGIN
    DECLARE index_exists INT DEFAULT 0;

    SELECT COUNT(*) INTO index_exists
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = table_name
      AND index_name = index_name;

    IF index_exists = 0 THEN
        SET @sql = CONCAT('ALTER TABLE `', table_name, '` ADD INDEX `', index_name, '` (', index_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- 1. 优惠券订单表：新增乐观锁版本号字段
CALL add_column_if_not_exists('tb_voucher_order', 'version', 'INT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号''', 'status');

-- 2. 优惠券订单表：新增关单时间字段
CALL add_column_if_not_exists('tb_voucher_order', 'cancel_time', 'TIMESTAMP NULL DEFAULT NULL COMMENT ''关单时间''', 'refund_time');

-- 3. 优惠券订单表：新增联合索引
CALL add_index_if_not_exists('tb_voucher_order', 'idx_user_voucher', '`user_id`, `voucher_id`');

-- 4. 到店预约表
DROP TABLE IF EXISTS `tb_reservation`;
CREATE TABLE `tb_reservation` (
  `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` BIGINT(20) UNSIGNED NOT NULL COMMENT '商家ID',
  `user_id` BIGINT(20) UNSIGNED NOT NULL COMMENT '用户ID',
  `reserve_time` DATETIME NOT NULL COMMENT '预约到店时间',
  `person_count` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '用餐人数',
  `phone` VARCHAR(11) NOT NULL COMMENT '联系电话',
  `status` TINYINT(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1待确认 2已确认 3已完成 4已取消',
  `remark` VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `create_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_shop_time` (`shop_id`, `reserve_time`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='到店预约表';

-- 5. 更新已有订单的 version 为 0（兼容旧数据）
UPDATE `tb_voucher_order` SET `version` = 0 WHERE `version` IS NULL;

-- 清理存储过程
DROP PROCEDURE IF EXISTS `add_column_if_not_exists`;
DROP PROCEDURE IF EXISTS `add_index_if_not_exists`;

SELECT '数据库迁移完成！' AS message;
