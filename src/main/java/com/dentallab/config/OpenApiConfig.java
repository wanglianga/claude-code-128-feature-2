package com.dentallab.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3.x 文档配置：除登录与健康检查外，接口通过 X-Auth-Token 请求头鉴权。 */
@Configuration
public class OpenApiConfig {

    public static final String TOKEN_SCHEME = "AuthToken";

    @Bean
    public OpenAPI dentalLabOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("牙科义齿加工订单与试戴返工服务 API")
                        .version("1.0.0")
                        .description("""
                                覆盖下单→八道工序→发货签收→试戴（咬合高/边缘不密合）→返工方案/诊所确认/
                                原因标记（设计/加工/医生取模）→订单结算→技工所同步月结→诊所确认/月结争议→财务确认全流程。
                                先调用 /api/auth/login 获取 token，点击右上角 Authorize 输入 token（无需 Bearer 前缀）。"""))
                .components(new Components().addSecuritySchemes(TOKEN_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Auth-Token")))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_SCHEME));
    }
}
