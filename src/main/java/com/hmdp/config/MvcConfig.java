package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 2023/1/19 15:01
 *
 * @author tfqy
 */

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/user/code",
                        "/shop/**",
                        "/blog/hot",
                        "/upload/**",
                        "/voucher/**"
                ).order(1);
//        order(1)表示优先级，数字越小优先级越高
        registry.addInterceptor(new RefreshTokenInterceptor(redisTemplate))
                .excludePathPatterns(
                        "/user/login",
                        "/user/code"
                ).order(0);
    }
}
