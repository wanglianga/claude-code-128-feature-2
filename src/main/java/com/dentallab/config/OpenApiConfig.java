package com.dentallab.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 文档：除登录/健康检查外所有接口需要 X-Auth-Token 请求头。 */
@Configuration
public class OpenApiConfig {

    private static final String TOKEN_HEADER = "X-Auth-Token";

    @Bean
    public OpenAPI dentalLabOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("牙科义齿加工协同服务 API")
                        .version("1.0.0")
                        .description("下单→生产八工序→配送签收→试戴→不密合返工（诊所确认时限/是否收费、"
                                + "技工所标记设计/加工/取模原因、责任分摊）→订单结算→月结（技工所同步、"
                                + "诊所确认、月结争议、财务确认）全流程接口。"))
                .components(new Components().addSecuritySchemes(TOKEN_HEADER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(TOKEN_HEADER)
                                .description("登录 /api/auth/login 返回的 token")))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_HEADER));
    }
}
