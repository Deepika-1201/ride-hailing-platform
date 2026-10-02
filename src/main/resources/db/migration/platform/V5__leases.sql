-- Named leases with fencing tokens (LLD §5.5). A lease's row is created by its first acquisition.

CREATE TABLE platform.leases (
    name       text        PRIMARY KEY,
    holder     text,
    token      bigint      NOT NULL DEFAULT 0,
    expires_at timestamptz
);
