-- Set when an admin disables sign-in, so the gateway can reject still-valid access tokens immediately.
ALTER TABLE account_profiles ADD COLUMN sign_in_disabled BOOLEAN NOT NULL DEFAULT FALSE;
