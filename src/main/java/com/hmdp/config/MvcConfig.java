package com.hmdp.config;

import com.hmdp.utils.RefreshToken;
import com.hmdp.utils.loginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry){
        //token刷新
        registry.addInterceptor(new RefreshToken(stringRedisTemplate)).order(0);
        //登录拦截
        registry.addInterceptor(new loginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/upload/**",
                        "voucher/**",
                        "/shop-typer/**"
                ).order(1);
    }
}
