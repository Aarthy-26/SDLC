package com.aava.datapowerai.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Story 104470: App Version Management / Version History – Backend
 *
 * Centralizes app-level authorization checks.
 */
// ASSUMPTION: not found in repo content
@Service
public class AppAuthorizationService {

    /**
     * ASSUMPTION: Since repo content does not include an app-level ACL model,
     * we enforce restore authorization via existing role model only.
     * Allowed roles: TOOL_ADMIN, PROJECT_ADMIN.
     */
    public boolean canRestore(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            String r = ga.getAuthority();
            if ("ROLE_TOOL_ADMIN".equals(r) || "ROLE_PROJECT_ADMIN".equals(r) || "ROLE_ADMIN".equals(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ASSUMPTION: Version listing requires authentication only.
     */
    public boolean canView(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }
}
