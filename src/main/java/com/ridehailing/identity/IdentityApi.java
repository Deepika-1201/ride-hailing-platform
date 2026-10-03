package com.ridehailing.identity;

import com.ridehailing.shared.UserRole;
import java.util.Set;
import java.util.UUID;

/** User accounts, for the modules that onboard people (LLD §2.2). */
public interface IdentityApi {

    /**
     * The phone's user ID: a new user with the roles, or an existing one with the roles added, so a rider who becomes
     * a driver keeps their account. Joins the caller's transaction.
     */
    UUID ensureUser(String phone, Set<UserRole> roles);
}
