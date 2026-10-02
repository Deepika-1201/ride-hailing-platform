-- Users, one-time-code challenges and refresh tokens (LLD §4.3, §12).

CREATE TABLE identity.users (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    phone      text        NOT NULL UNIQUE CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    roles      text[]      NOT NULL CHECK (roles <@ ARRAY['RIDER', 'DRIVER', 'OPS', 'ADMIN'] AND cardinality(roles) > 0),
    status     text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    version    int         NOT NULL DEFAULT 0
);

CREATE TABLE identity.otp_challenges (
    id          uuid        PRIMARY KEY,
    phone       text        NOT NULL,
    code_hmac   bytea       NOT NULL,
    attempts    smallint    NOT NULL DEFAULT 0,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    request_ip  inet,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX otp_by_phone ON identity.otp_challenges (phone, created_at DESC);

CREATE TABLE identity.refresh_tokens (
    id         uuid        PRIMARY KEY,
    family_id  uuid        NOT NULL,
    parent_id  uuid,
    user_id    uuid        NOT NULL REFERENCES identity.users (id),
    token_hash bytea       NOT NULL UNIQUE,
    issued_at  timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    rotated_at timestamptz,
    revoked_at timestamptz
);

CREATE INDEX refresh_by_family ON identity.refresh_tokens (family_id);
CREATE INDEX refresh_by_parent ON identity.refresh_tokens (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX refresh_expiry ON identity.refresh_tokens (expires_at);
