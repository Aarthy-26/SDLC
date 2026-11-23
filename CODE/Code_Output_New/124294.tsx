/*
Story ID: 124294
Title: UI: SSO Integration (Login with AAVA Identity)
Chosen language: TypeScript — the repo is a Vite React TypeScript UI codebase with auth-related modules and a Login page, so this story belongs in the existing frontend layer.
What this code does: Adds a repo-style React login module that provides SSO sign-in, PKCE generation, OIDC redirect handling, logout/session expiry handling, role mapping, provisioning payload shaping, audit event emission, and clear user-facing auth error states without storing tokens in localStorage.
Assumptions made:
- Comments, attachments, and related links were not returned by the work item tool, so this file implements only what was present.
- The backend is assumed to expose SSO endpoints under /api/auth/sso/* for config, callback exchange, session lookup, and logout.
- ASSUMPTION: not found in repo content — exact backend SSO DTO contract, so request/response types are declared in this file.
*/

import React, { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ShieldCheck, LogIn, AlertTriangle, Loader2, LogOut } from "lucide-react";
import { getRuntimeEnv } from "../utils/runtimeEnv";
import { clearLogoutSensitiveState } from "../utils/logoutCleanup";

type RoleName =
  | "ADMIN"
  | "EDITOR"
  | "VIEWER"
  | "ANALYST"
  | "NO_ACCESS";

type AuditEventType =
  | "login_started"
  | "login_succeeded"
  | "login_failed"
  | "logout"
  | "session_expired"
  | "user_provisioned"
  | "access_denied";

type AuthStage =
  | "idle"
  | "loading"
  | "redirecting"
  | "exchanging"
  | "authenticated"
  | "error";

interface SsoRuntimeConfig {
  enabled: boolean;
  localLoginEnabled: boolean;
  providerName: string;
  authorizationEndpoint: string;
  clientId: string;
  redirectUri: string;
  scope: string;
  audience?: string;
  issuer?: string;
  tenant?: string;
}

interface SsoSessionResponse {
  authenticated: boolean;
  expiresAt?: string;
  sessionId?: string;
  requestedPath?: string;
  user?: ProvisionedUser;
}

interface SsoCallbackExchangeRequest {
  code: string;
  codeVerifier: string;
  state: string;
  redirectUri: string;
  requestedPath: string;
}

interface ProvisionedUser {
  uniqueId: string;
  email: string;
  name: string;
  roles: RoleName[];
  groups: string[];
  isFirstLogin: boolean;
}

interface SsoCallbackExchangeResponse {
  sessionId: string;
  expiresAt: string;
  requestedPath?: string;
  user: ProvisionedUser;
}

interface SsoErrorState {
  code:
    | "sso_unavailable"
    | "access_denied"
    | "login_failed"
    | "invalid_callback"
    | "session_expired";
  title: string;
  message: string;
}

interface PkceStateRecord {
  state: string;
  codeVerifier: string;
  requestedPath: string;
  createdAt: number;
}

const LOGIN_COPY = {
  title: "Sign in to AAVA Identity",
  subtitle:
    "Use your organization single sign-on to access DataPowerAI securely.",
  button: "Sign in with SSO",
};

const STORAGE_KEYS = {
  pkce: "dpai_sso_pkce",
  expiryRedirect: "dpai_sso_expired_redirect",
};

const DEFAULT_ERROR: SsoErrorState = {
  code: "login_failed",
  title: "Unable to sign you in",
  message: "The login attempt did not complete. Please try again.",
};

function DatapowerAiSsoLogin(): JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const [config, setConfig] = useState<SsoRuntimeConfig | null>(null);
  const [session, setSession] = useState<SsoSessionResponse | null>(null);
  const [stage, setStage] = useState<AuthStage>("loading");
  const [error, setError] = useState<SsoErrorState | null>(null);
  const [busyText, setBusyText] = useState("Loading sign-in options…");
  const expiryTimerRef = useRef<number | null>(null);

  const isCallback = useMemo(() => {
    const params = new URLSearchParams(location.search);
    return params.has("code") || params.has("error") || params.has("state");
  }, [location.search]);

  useEffect(() => {
    void initialize();
    return () => {
      if (expiryTimerRef.current) {
        window.clearTimeout(expiryTimerRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (!session?.expiresAt) {
      return;
    }
    if (expiryTimerRef.current) {
      window.clearTimeout(expiryTimerRef.current);
    }
    const expiryMs = new Date(session.expiresAt).getTime() - Date.now();
    if (expiryMs <= 0) {
      handleSessionExpired();
      return;
    }
    expiryTimerRef.current = window.setTimeout(() => {
      handleSessionExpired();
    }, expiryMs);
  }, [session?.expiresAt]);

  const initialize = async (): Promise<void> => {
    try {
      setBusyText("Loading sign-in options…");
      setStage("loading");
      const runtimeConfig = await fetchSsoConfig();
      setConfig(runtimeConfig);

      const currentSession = await fetchCurrentSession();
      setSession(currentSession);

      if (currentSession.authenticated && currentSession.user) {
        setStage("authenticated");
        redirectAfterLogin(currentSession.requestedPath || "/");
        return;
      }

      if (isCallback) {
        await handleCallback(runtimeConfig);
        return;
      }

      const params = new URLSearchParams(location.search);
      if (params.get("reason") === "expired") {
        setError({
          code: "session_expired",
          title: "Session expired",
          message: "Your session ended. Please sign in again.",
        });
      }
      setStage("idle");
    } catch {
      setStage("error");
      setError({
        code: "sso_unavailable",
        title: "Sign-in is unavailable",
        message:
          "The identity provider or sign-in service is currently unavailable. Please try again later.",
      });
      emitAuditEvent("login_failed", {
        reason: "config_or_session_load_failed",
      });
    }
  };

  const handleSsoSignIn = async (): Promise<void> => {
    if (!config?.enabled) {
      setError({
        code: "sso_unavailable",
        title: "SSO is disabled",
        message: "Single sign-on is not enabled in this environment.",
      });
      return;
    }

    const requestedPath = resolveRequestedPath();
    const codeVerifier = generateRandomString(64);
    const state = generateRandomString(48);
    const codeChallenge = await createCodeChallenge(codeVerifier);

    const pkceRecord: PkceStateRecord = {
      state,
      codeVerifier,
      requestedPath,
      createdAt: Date.now(),
    };
    sessionStorage.setItem(STORAGE_KEYS.pkce, JSON.stringify(pkceRecord));

    const authorizeUrl = new URL(config.authorizationEndpoint);
    authorizeUrl.searchParams.set("client_id", config.clientId);
    authorizeUrl.searchParams.set("response_type", "code");
    authorizeUrl.searchParams.set("redirect_uri", config.redirectUri);
    authorizeUrl.searchParams.set("scope", config.scope);
    authorizeUrl.searchParams.set("state", state);
    authorizeUrl.searchParams.set("code_challenge", codeChallenge);
    authorizeUrl.searchParams.set("code_challenge_method", "S256");
    if (config.audience) {
      authorizeUrl.searchParams.set("audience", config.audience);
    }

    setBusyText("Redirecting to your identity provider…");
    setStage("redirecting");
    setError(null);
    emitAuditEvent("login_started", {
      provider: config.providerName,
      requestedPath,
    });

    window.location.assign(authorizeUrl.toString());
  };

  const handleCallback = async (runtimeConfig: SsoRuntimeConfig): Promise<void> => {
    const params = new URLSearchParams(location.search);
    const providerError = params.get("error");
    if (providerError) {
      const denied = providerError === "access_denied";
      const failedState: SsoErrorState = denied
        ? {
            code: "access_denied",
            title: "Access denied",
            message:
              "Your sign-in was denied by the identity provider or you do not have a mapped role.",
          }
        : DEFAULT_ERROR;
      setError(failedState);
      setStage("error");
      emitAuditEvent(denied ? "access_denied" : "login_failed", {
        providerError,
        providerErrorDescription: params.get("error_description") || "",
      });
      return;
    }

    const code = params.get("code");
    const state = params.get("state");
    const storedRecord = readPkceRecord();
    if (!code || !state || !storedRecord || storedRecord.state !== state) {
      setStage("error");
      setError({
        code: "invalid_callback",
        title: "Invalid sign-in response",
        message:
          "The sign-in response could not be validated. Please start the login flow again.",
      });
      emitAuditEvent("login_failed", {
        reason: "callback_state_mismatch",
      });
      return;
    }

    setBusyText("Completing sign-in…");
    setStage("exchanging");
    setError(null);

    const result = await exchangeAuthorizationCode({
      code,
      state,
      codeVerifier: storedRecord.codeVerifier,
      redirectUri: runtimeConfig.redirectUri,
      requestedPath: storedRecord.requestedPath,
    });

    clearPkceRecord();
    setSession({
      authenticated: true,
      expiresAt: result.expiresAt,
      sessionId: result.sessionId,
      requestedPath: result.requestedPath,
      user: result.user,
    });
    setStage("authenticated");

    if (result.user.isFirstLogin) {
      emitAuditEvent("user_provisioned", buildProvisioningAuditPayload(result.user));
    }
    emitAuditEvent("login_succeeded", buildProvisioningAuditPayload(result.user));

    if (!hasEffectiveAccess(result.user.roles)) {
      setStage("error");
      setError({
        code: "access_denied",
        title: "Access denied",
        message:
          "Your account was authenticated, but no permitted DataPowerAI role was mapped.",
      });
      emitAuditEvent("access_denied", buildProvisioningAuditPayload(result.user));
      return;
    }

    redirectAfterLogin(result.requestedPath || storedRecord.requestedPath || "/");
  };

  const handleLogout = async (): Promise<void> => {
    try {
      await fetchJson<void>(buildApiUrl("/auth/sso/logout"), {
        method: "POST",
      });
    } finally {
      clearPkceRecord();
      clearLogoutSensitiveState();
      emitAuditEvent("logout", {
        sessionId: session?.sessionId || "",
      });
      navigate("/auth/login", { replace: true });
      window.location.reload();
    }
  };

  const handleSessionExpired = (): void => {
    sessionStorage.setItem(STORAGE_KEYS.expiryRedirect, resolveRequestedPath());
    clearPkceRecord();
    clearLogoutSensitiveState();
    emitAuditEvent("session_expired", {
      sessionId: session?.sessionId || "",
    });
    navigate("/auth/login?reason=expired", { replace: true });
    setSession(null);
    setStage("idle");
    setError({
      code: "session_expired",
      title: "Session expired",
      message: "Your session ended. Please sign in again.",
    });
  };

  const currentRoleText = session?.user?.roles?.join(", ") || "No roles assigned";

  return (
    <div className="min-h-screen bg-slate-950 text-white">
      <div className="mx-auto flex min-h-screen max-w-6xl items-center justify-center px-6 py-10">
        <div className="grid w-full overflow-hidden rounded-3xl border border-white/10 bg-white/5 shadow-2xl backdrop-blur md:grid-cols-[1.1fr_0.9fr]">
          <section className="flex flex-col justify-between p-8 md:p-12">
            <div>
              <div className="mb-6 inline-flex items-center gap-3 rounded-full border border-emerald-400/20 bg-emerald-400/10 px-4 py-2 text-sm text-emerald-200">
                <ShieldCheck className="h-4 w-4" />
                Secure organization sign-in
              </div>
              <h1 className="text-4xl font-semibold tracking-tight">
                {LOGIN_COPY.title}
              </h1>
              <p className="mt-4 max-w-xl text-base leading-7 text-slate-300">
                {LOGIN_COPY.subtitle}
              </p>
            </div>

            <div className="mt-10 grid gap-4 sm:grid-cols-2">
              <FeatureTile
                title="OIDC + PKCE"
                description="Starts an authorization code flow with PKCE and never stores tokens in localStorage."
              />
              <FeatureTile
                title="Role-aware access"
                description="Maps IdP groups to DataPowerAI roles and blocks unmapped access."
              />
              <FeatureTile
                title="Automatic provisioning"
                description="Shapes first-login claims so the backend can create or match a user record without duplicates."
              />
              <FeatureTile
                title="Session-safe logout"
                description="Clears session state, handles expiry redirects, and keeps existing APIs untouched."
              />
            </div>
          </section>

          <section className="border-t border-white/10 bg-slate-900/80 p-8 md:border-t-0 md:border-l md:p-12">
            <div className="mx-auto flex max-w-md flex-col gap-6">
              <div>
                <p className="text-sm uppercase tracking-[0.2em] text-slate-400">
                  AAVA Identity
                </p>
                <h2 className="mt-2 text-2xl font-semibold text-white">
                  {config?.providerName || "Single sign-on"}
                </h2>
                <p className="mt-2 text-sm leading-6 text-slate-400">
                  Use your organization account. Requested pages are restored after successful sign-in.
                </p>
              </div>

              {stage !== "authenticated" && (
                <button
                  type="button"
                  onClick={() => void handleSsoSignIn()}
                  disabled={stage === "loading" || stage === "redirecting" || stage === "exchanging" || !config?.enabled}
                  className="inline-flex items-center justify-center gap-3 rounded-2xl bg-emerald-500 px-5 py-4 text-base font-semibold text-slate-950 transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:bg-slate-700 disabled:text-slate-300"
                >
                  {stage === "redirecting" || stage === "exchanging" || stage === "loading" ? (
                    <Loader2 className="h-5 w-5 animate-spin" />
                  ) : (
                    <LogIn className="h-5 w-5" />
                  )}
                  {LOGIN_COPY.button}
                </button>
              )}

              {stage === "authenticated" && session?.user && (
                <div className="rounded-2xl border border-emerald-400/20 bg-emerald-400/10 p-5">
                  <p className="text-sm font-medium text-emerald-200">Signed in</p>
                  <h3 className="mt-1 text-lg font-semibold text-white">
                    {session.user.name}
                  </h3>
                  <p className="mt-1 text-sm text-slate-300">{session.user.email}</p>
                  <p className="mt-3 text-xs uppercase tracking-[0.18em] text-slate-400">
                    Roles
                  </p>
                  <p className="mt-1 text-sm text-white">{currentRoleText}</p>
                  <button
                    type="button"
                    onClick={() => void handleLogout()}
                    className="mt-5 inline-flex items-center gap-2 rounded-xl border border-white/15 px-4 py-2 text-sm font-medium text-white hover:bg-white/5"
                  >
                    <LogOut className="h-4 w-4" />
                    Logout
                  </button>
                </div>
              )}

              {(stage === "loading" || stage === "redirecting" || stage === "exchanging") && (
                <StatusNotice text={busyText} kind="info" />
              )}

              {error && (
                <StatusNotice
                  text={`${error.title}: ${error.message}`}
                  kind="error"
                />
              )}

              {!config?.enabled && stage !== "loading" && (
                <StatusNotice
                  text={
                    config?.localLoginEnabled
                      ? "SSO is disabled in this environment. Local login may still be available."
                      : "SSO is disabled and local login is not available in this environment."
                  }
                  kind="warning"
                />
              )}

              <div className="rounded-2xl border border-white/10 bg-white/[0.03] p-5 text-sm text-slate-300">
                <p className="font-medium text-white">Environment-backed settings</p>
                <ul className="mt-3 space-y-2 text-sm text-slate-400">
                  <li>Tenant: {maskConfigValue(config?.tenant)}</li>
                  <li>Client ID: {maskConfigValue(config?.clientId)}</li>
                  <li>Redirect URI: {config?.redirectUri || "Not configured"}</li>
                  <li>Issuer: {maskConfigValue(config?.issuer)}</li>
                </ul>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}

function FeatureTile({ title, description }: { title: string; description: string }): JSX.Element {
  return (
    <div className="rounded-2xl border border-white/10 bg-white/[0.03] p-5">
      <h3 className="text-base font-semibold text-white">{title}</h3>
      <p className="mt-2 text-sm leading-6 text-slate-400">{description}</p>
    </div>
  );
}

function StatusNotice({ text, kind }: { text: string; kind: "info" | "warning" | "error" }): JSX.Element {
  const palette =
    kind === "error"
      ? "border-red-400/20 bg-red-400/10 text-red-100"
      : kind === "warning"
        ? "border-amber-400/20 bg-amber-400/10 text-amber-100"
        : "border-sky-400/20 bg-sky-400/10 text-sky-100";

  return (
    <div className={`rounded-2xl border p-4 text-sm leading-6 ${palette}`}>
      <div className="flex items-start gap-3">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
        <span>{text}</span>
      </div>
    </div>
  );
}

async function fetchSsoConfig(): Promise<SsoRuntimeConfig> {
  return fetchJson<SsoRuntimeConfig>(buildApiUrl("/auth/sso/config"), {
    method: "GET",
  });
}

async function fetchCurrentSession(): Promise<SsoSessionResponse> {
  return fetchJson<SsoSessionResponse>(buildApiUrl("/auth/sso/session"), {
    method: "GET",
  });
}

async function exchangeAuthorizationCode(
  payload: SsoCallbackExchangeRequest,
): Promise<SsoCallbackExchangeResponse> {
  return fetchJson<SsoCallbackExchangeResponse>(buildApiUrl("/auth/sso/callback"), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
}

async function fetchJson<T>(url: string, init: RequestInit): Promise<T> {
  const response = await fetch(url, {
    credentials: "include",
    ...init,
    headers: {
      Accept: "application/json",
      ...(init.headers || {}),
    },
  });

  if (!response.ok) {
    throw new Error(`Request failed with status ${response.status}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

function buildApiUrl(path: string): string {
  const runtime = getRuntimeEnv();
  const baseUrl = String(runtime.VITE_API_BASE_URL || "").replace(/\/+$/, "");
  const endpoint = String(runtime.VITE_API_BASE_ENDPOINT || "/api/").replace(/\/$/, "");
  return `${baseUrl}${endpoint}${path.startsWith("/") ? path : `/${path}`}`;
}

function resolveRequestedPath(): string {
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const expiredRedirect = sessionStorage.getItem(STORAGE_KEYS.expiryRedirect);
  if (expiredRedirect) {
    sessionStorage.removeItem(STORAGE_KEYS.expiryRedirect);
    return expiredRedirect;
  }
  if (current.startsWith("/auth/login")) {
    const redirect = new URLSearchParams(window.location.search).get("redirect");
    return redirect || "/";
  }
  return current || "/";
}

function redirectAfterLogin(path: string): void {
  const target = path && path !== "/auth/login" ? path : "/";
  window.location.replace(target);
}

function readPkceRecord(): PkceStateRecord | null {
  const raw = sessionStorage.getItem(STORAGE_KEYS.pkce);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as PkceStateRecord;
    if (Date.now() - parsed.createdAt > 15 * 60 * 1000) {
      clearPkceRecord();
      return null;
    }
    return parsed;
  } catch {
    clearPkceRecord();
    return null;
  }
}

function clearPkceRecord(): void {
  sessionStorage.removeItem(STORAGE_KEYS.pkce);
}

function hasEffectiveAccess(roles: RoleName[]): boolean {
  return roles.some((role) => role !== "NO_ACCESS");
}

function buildProvisioningAuditPayload(user: ProvisionedUser): Record<string, unknown> {
  return {
    uniqueId: user.uniqueId,
    email: user.email,
    name: user.name,
    roles: user.roles,
    groups: user.groups,
    firstLogin: user.isFirstLogin,
  };
}

function emitAuditEvent(type: AuditEventType, detail: Record<string, unknown>): void {
  window.dispatchEvent(
    new CustomEvent("dpai:auth-audit", {
      detail: {
        type,
        timestamp: new Date().toISOString(),
        ...detail,
      },
    }),
  );
}

function maskConfigValue(value: string | undefined): string {
  if (!value) {
    return "Not configured";
  }
  if (value.length <= 8) {
    return "Configured";
  }
  return `${value.slice(0, 4)}••••${value.slice(-4)}`;
}

function generateRandomString(length: number): string {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) =>
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"[
      byte % 66
    ] || "A",
  ).join("");
}

async function createCodeChallenge(codeVerifier: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(codeVerifier),
  );
  return base64UrlEncode(new Uint8Array(digest));
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export default DatapowerAiSsoLogin;
