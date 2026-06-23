package com.yuzugame;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger 文档配置。
 *
 * <p>启动后访问：
 * <ul>
 *   <li>API 文档（JSON）：{@code /v3/api-docs}</li>
 *   <li>Swagger UI：{@code /swagger-ui.html}</li>
 * </ul></p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI yuzuGameOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("YuzuGame API")
                        .description("Whispers of the Millennium Tower - LLM 文字冒险游戏 API")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("YuzuGame Team"))
                        .license(new License()
                                .name("Proprietary")));
    }
}
