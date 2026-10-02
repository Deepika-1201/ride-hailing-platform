package com.ridehailing.shared;

/** What a user may act as (LLD §12.4); a user may hold several. Not to be confused with a process's runtime role. */
public enum UserRole {
    RIDER,
    DRIVER,
    OPS,
    ADMIN
}
