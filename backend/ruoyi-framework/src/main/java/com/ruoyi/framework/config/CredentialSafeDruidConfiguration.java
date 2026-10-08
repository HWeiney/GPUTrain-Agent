package com.ruoyi.framework.config;

import java.util.Locale;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.proxy.jdbc.StatementProxy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Prevent SSH account parameters from entering Druid statistics or slow SQL logs. */
@Configuration(proxyBeanMethods = false)
public class CredentialSafeDruidConfiguration
{
    @Bean
    @ConfigurationProperties("spring.datasource.druid.filter.stat")
    @ConditionalOnProperty(prefix = "spring.datasource.druid.filter.stat", name = "enabled", havingValue = "true")
    public StatFilter credentialSafeStatFilter()
    {
        return new ServerAccountStatFilter();
    }

    private static class ServerAccountStatFilter extends StatFilter
    {
        @Override
        protected String buildSlowParameters(StatementProxy statement)
        {
            String sql = statement.getLastExecuteSql();
            if (sql != null && sql.toLowerCase(Locale.ROOT).contains("train_server_account"))
            {
                // StatFilter uses this value for both lastSlowParameters and slow SQL logging.
                return "[]";
            }
            return super.buildSlowParameters(statement);
        }
    }
}
