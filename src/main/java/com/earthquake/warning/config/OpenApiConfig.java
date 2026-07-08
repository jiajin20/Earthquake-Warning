package com.earthquake.warning.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 配置
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI earthquakeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("地震预警系统 API")
                        .description("""
                                ## 功能说明
                                                                
                                ### 数据来源
                                对接 **Wolfx Open API** (`wss://ws-api.wolfx.jp/cenc_eqlist和wss://ws-api.wolfx.jp/cenc_eew`)，
                                实时获取中国地震台网 (CENC) 发布的全球地震信息。
                                                                
                                ### 核心功能
                                - **震中距离计算** - Haversine 大圆距离公式 (6371km 地球半径)
                                - **本地烈度估算** - GB 18306-2015 中国东部地震烈度衰减模型 + GB 50011 场地放大
                                - **影响等级判定** - 0~5 级（无感→毁灭性）
                                - **P波/S波到达时间** - IASPEI-91 一维地球分层速度模型，P/S 波速比 ≈1.73
                                - **防护建议生成** - 根据等级和倒计时智能建议
                                - **Bark 推送** - 实时推送到 iPhone（多设备、分级策略、异步非阻塞）
                                                                
                                ### 模拟测试
                                通过 `/api/earthquake/simulate` 接口可以模拟任意地震事件，
                                用于测试计算逻辑和 Bark 推送通道。
                                                                
                                ### 配置
                                在 `application.yml` 中修改监测点经纬度、Bark Device Key、
                                预警阈值等参数。
                                """)
                        .version("5.6.0")
                        .contact(new Contact()
                                .name("Earthquake Warning System")
                                .url("https://github.com/jiajin20/Earthquake-Warning"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }
}
