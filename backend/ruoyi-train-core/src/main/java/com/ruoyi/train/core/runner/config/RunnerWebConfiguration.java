package com.ruoyi.train.core.runner.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import com.ruoyi.train.core.runner.web.CachedBodyHttpServletRequest;
import com.ruoyi.train.core.runner.web.RunnerAuthenticationInterceptor;

@Configuration
public class RunnerWebConfiguration implements WebMvcConfigurer
{
    private final RunnerAuthenticationInterceptor interceptor;

    public RunnerWebConfiguration(RunnerAuthenticationInterceptor interceptor)
    {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(interceptor).addPathPatterns("/runner-api/v1/**")
                .excludePathPatterns("/runner-api/v1/register");
    }

    @Bean
    public FilterRegistrationBean<Filter> runnerBodyCachingFilter()
    {
        Filter filter = (request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            String path = http.getRequestURI().substring(http.getContextPath().length());
            if (RunnerAuthenticationInterceptor.isSignedPath(path))
                chain.doFilter(new CachedBodyHttpServletRequest(http), response);
            else
                chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("runnerBodyCachingFilter");
        registration.addUrlPatterns("/runner-api/v1/*");
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE);
        return registration;
    }
}
