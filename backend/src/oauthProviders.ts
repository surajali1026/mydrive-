export type SupportedProviderId =
  | "google_drive"
  | "onedrive"
  | "dropbox"
  | "box"
  | "pcloud"
  | "koofr";

export interface OAuthProviderSpec {
  id: SupportedProviderId;
  authorizeEndpoint: string;
  tokenEndpoint: string;
  scope: string;
  supportsPkce: boolean;
  clientIdEnv: string;
  clientSecretEnv: string;
  extraAuthParams?: Record<string, string>;
}

/**
 * Official OAuth 2.0 provider endpoints.
 * MEGA is intentionally omitted (`Integration Pending`) and TeraBox is strictly excluded.
 */
export const OAUTH_PROVIDERS: Record<SupportedProviderId, OAuthProviderSpec> = {
  google_drive: {
    id: "google_drive",
    authorizeEndpoint: "https://accounts.google.com/o/oauth2/v2/auth",
    tokenEndpoint: "https://oauth2.googleapis.com/token",
    scope: "https://www.googleapis.com/auth/drive.file openid email profile",
    supportsPkce: true,
    clientIdEnv: "GOOGLE_CLIENT_ID",
    clientSecretEnv: "GOOGLE_CLIENT_SECRET",
    extraAuthParams: { access_type: "offline", prompt: "select_account consent" },
  },
  onedrive: {
    id: "onedrive",
    authorizeEndpoint: "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
    tokenEndpoint: "https://login.microsoftonline.com/common/oauth2/v2.0/token",
    scope: "Files.ReadWrite offline_access User.Read",
    supportsPkce: true,
    clientIdEnv: "ONEDRIVE_CLIENT_ID",
    clientSecretEnv: "ONEDRIVE_CLIENT_SECRET",
  },
  dropbox: {
    id: "dropbox",
    authorizeEndpoint: "https://www.dropbox.com/oauth2/authorize",
    tokenEndpoint: "https://api.dropboxapi.com/oauth2/token",
    scope: "",
    supportsPkce: true,
    clientIdEnv: "DROPBOX_CLIENT_ID",
    clientSecretEnv: "DROPBOX_CLIENT_SECRET",
    extraAuthParams: { token_access_type: "offline" },
  },
  box: {
    id: "box",
    authorizeEndpoint: "https://account.box.com/api/oauth2/authorize",
    tokenEndpoint: "https://api.box.com/oauth2/token",
    scope: "",
    supportsPkce: false,
    clientIdEnv: "BOX_CLIENT_ID",
    clientSecretEnv: "BOX_CLIENT_SECRET",
  },
  pcloud: {
    id: "pcloud",
    authorizeEndpoint: "https://my.pcloud.com/oauth2/authorize",
    tokenEndpoint: "https://api.pcloud.com/oauth2_token",
    scope: "",
    supportsPkce: false,
    clientIdEnv: "PCLOUD_CLIENT_ID",
    clientSecretEnv: "PCLOUD_CLIENT_SECRET",
  },
  koofr: {
    id: "koofr",
    authorizeEndpoint: "https://app.koofr.net/oauth2/auth",
    tokenEndpoint: "https://app.koofr.net/oauth2/token",
    scope: "public",
    supportsPkce: false,
    clientIdEnv: "KOOFR_CLIENT_ID",
    clientSecretEnv: "KOOFR_CLIENT_SECRET",
  },
};

export function getProviderSpec(providerId: string): OAuthProviderSpec | null {
  return (OAUTH_PROVIDERS as Record<string, OAuthProviderSpec | undefined>)[providerId] ?? null;
}
