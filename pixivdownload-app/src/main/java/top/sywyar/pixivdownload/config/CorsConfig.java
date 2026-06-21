package top.sywyar.pixivdownload.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        
        // 仅允许 Pixiv 网站（油猴脚本跨域请求来源）
        config.addAllowedOrigin("https://www.pixiv.net");
        config.addAllowedOrigin("https://pixiv.net");
        
        // 允许所有HTTP方法
        config.addAllowedMethod("*");
        
        // 允许所有请求头
        config.addAllowedHeader("*");
        
        // 允许携带凭证（如果需要）
        config.setAllowCredentials(true);
        
        // 预检请求的缓存时间（秒）
        config.setMaxAge(3600L);
        
        // 注册CORS配置，应用到所有路径
        source.registerCorsConfiguration("/**", config);
        
        return new CorsFilter(source);
    }
}

