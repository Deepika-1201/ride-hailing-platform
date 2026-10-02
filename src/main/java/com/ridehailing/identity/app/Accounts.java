package com.ridehailing.identity.app;

import com.ridehailing.platform.ApiException;
import org.springframework.http.HttpStatus;

final class Accounts {

    private Accounts() {
    }

    static ApiException disabled() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account is disabled.");
    }
}
