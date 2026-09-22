package cn.observe.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 「最高层」裸项目 —— 目标应用什么都没有，插件负责补齐一切。
 *
 * <p>注意本类与整个 {@code cn.observe.demo} 包下<b>没有任何观测相关代码</b>。
 */
@SpringBootApplication
public class DemoApp {

    public static void main(String[] args) {
        SpringApplication.run(DemoApp.class, args);
    }
}
