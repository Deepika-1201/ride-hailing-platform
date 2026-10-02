package com.ridehailing.platform;

/**
 * One idempotent command: {@code principal} separates key spaces (the caller's user ID), {@code operation} is the
 * method and concrete path, and {@code body} the parsed request body, which with the operation forms the request hash.
 */
public record IdempotentCall(String principal, String key, String operation, Object body) {
}
