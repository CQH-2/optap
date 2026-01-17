package com.iimsoft.schduler.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 简单的日志测试类
 */
public class LogTest {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(LogTest.class);
    
    public static void main(String[] args) {
        LOGGER.trace("This is TRACE level");
        LOGGER.debug("This is DEBUG level");
        LOGGER.info("This is INFO level - 日志测试成功！");
        LOGGER.warn("This is WARN level");
        LOGGER.error("This is ERROR level");
        
        System.out.println("\n日志级别说明：");
        System.out.println("- TRACE/DEBUG: 默认不显示");
        System.out.println("- INFO/WARN/ERROR: 应该都能显示");
    }
}
