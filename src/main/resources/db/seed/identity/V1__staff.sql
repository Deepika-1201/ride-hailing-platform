-- Local and demo staff accounts (LLD §4.9). They sign in like anyone else, with the local profile's fixed code.

INSERT INTO identity.users (id, phone, roles) VALUES
    ('0199a3f0-0000-7000-8000-000000000001', '+919000000001', ARRAY['OPS']),
    ('0199a3f0-0000-7000-8000-000000000002', '+919000000002', ARRAY['ADMIN'])
ON CONFLICT (phone) DO NOTHING;
