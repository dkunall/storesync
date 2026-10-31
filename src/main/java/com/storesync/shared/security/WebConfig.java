package com.storesync.shared.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final CallerResolver callers;

    WebConfig(CallerResolver callers) {
        this.callers = callers;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callers);
    }
}
