package com.ruoyi.framework.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import com.ruoyi.common.core.domain.model.LoginUser;

class McpPermissionAuthorizationManagerTest
{
    private final McpPermissionAuthorizationManager manager = new McpPermissionAuthorizationManager();

    @Test
    void allowsOnlyAuthenticatedUsersWithJobViewOrAdminPermission()
    {
        assertTrue(manager.hasJobView(authentication(Set.of("train:job:view"))));
        assertTrue(manager.hasJobView(authentication(Set.of("*:*:*"))));
        assertFalse(manager.hasJobView(authentication(Set.of("train:overview:view"))));
        assertFalse(manager.hasJobView(UsernamePasswordAuthenticationToken.unauthenticated("anonymous", null)));
    }

    private static Authentication authentication(Set<String> permissions)
    {
        LoginUser user = new LoginUser();
        user.setPermissions(permissions);
        return UsernamePasswordAuthenticationToken.authenticated(user, null, Set.of());
    }
}
