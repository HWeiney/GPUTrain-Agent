package com.ruoyi.framework.config;

import java.util.Set;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.constant.Constants;

/** Applies the job-view permission to the MCP servlet endpoint outside MVC method security. */
public final class McpPermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext>
{
    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context)
    {
        return new AuthorizationDecision(hasJobView(authentication.get()));
    }

    boolean hasJobView(Authentication authentication)
    {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser user))
            return false;
        Set<String> permissions = user.getPermissions();
        return permissions != null && (permissions.contains("train:job:view")
                || permissions.contains(Constants.ALL_PERMISSION));
    }
}
