package com.ridehailing.identity.app;

import com.ridehailing.identity.IdentityApi;
import com.ridehailing.identity.db.Users;
import com.ridehailing.shared.UserRole;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityService implements IdentityApi {

    private final Users users;

    IdentityService(Users users) {
        this.users = users;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID ensureUser(String phone, Set<UserRole> roles) {
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("A user needs at least one role");
        }
        return users.ensure(phone, roles);
    }
}
