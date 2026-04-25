package com.classicchatreader.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PublicApiGuardMvcConfig implements WebMvcConfigurer {

    private final PublicApiGuardInterceptor publicApiGuardInterceptor;

    public PublicApiGuardMvcConfig(PublicApiGuardInterceptor publicApiGuardInterceptor) {
        this.publicApiGuardInterceptor = publicApiGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(publicApiGuardInterceptor)
                .addPathPatterns("/api/**");
    }
}
