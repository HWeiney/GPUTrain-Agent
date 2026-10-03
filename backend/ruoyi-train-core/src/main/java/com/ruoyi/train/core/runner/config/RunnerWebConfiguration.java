package com.ruoyi.train.core.runner.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.ruoyi.train.core.runner.web.CachedBodyHttpServletRequest;
import com.ruoyi.train.core.runner.web.RunnerAuthenticationInterceptor;
import com.ruoyi.train.core.runner.web.RunnerProtocolExceptionHandler;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

@Configuration
public class RunnerWebConfiguration implements WebMvcConfigurer
{
    private final RunnerAuthenticationInterceptor interceptor;
    private final RunnerSecurityProperties properties;

    public RunnerWebConfiguration(RunnerAuthenticationInterceptor interceptor, RunnerSecurityProperties properties)
    {
        this.interceptor = interceptor;
        this.properties = properties;
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
            {
                CachedBodyHttpServletRequest cached;
                try
                {
                    cached = new CachedBodyHttpServletRequest(http, properties.getMaxRequestBodyBytes());
                }
                catch (RunnerProtocolException failure)
                {
                    RunnerProtocolExceptionHandler.writeError((HttpServletResponse) response, failure);
                    return;
                }
                chain.doFilter(cached, response);
            }
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
