// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.github.oauth;

import com.google.common.base.CharMatcher;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gerrit.extensions.client.AuthType;
import com.google.gerrit.httpd.CanonicalWebUrl;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.github.oauth.OAuthProtocol.Scope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.eclipse.jgit.lib.Config;

@Singleton
public class GitHubOAuthConfig {
  private final Config config;
  private final CanonicalWebUrl canonicalWebUrl;

  public static final String CONF_SECTION = "github";
  public static final String GITHUB_OAUTH_AUTHORIZE = "/login/oauth/authorize";
  public static final String GITHUB_OAUTH_ACCESS_TOKEN = "/login/oauth/access_token";
  public static final String GITHUB_GET_USER = "/user";
  public static final String GERRIT_OAUTH_FINAL = "/oauth";
  public static final String GITHUB_URL_DEFAULT = "https://github.com";
  public static final String GITHUB_API_URL_DEFAULT = "https://api.github.com";
  public static final String GERRIT_LOGIN = "/login";
  public static final String GERRIT_LOGOUT = "/logout";
  public static final String GITHUB_PLUGIN_OAUTH_SCOPE = "/plugins/github-plugin/static/scope.html";

  public final String gitHubUrl;
  public final String gitHubApiUrl;
  public final String gitHubClientId;
  public final String gitHubClientSecret;
  public final String logoutRedirectUrl;
  public final String httpHeader;
  public final String gitHubOAuthUrl;
  public final String gitHubOAuthAccessTokenUrl;
  public final boolean enabled;

  @Getter public final Map<ScopeKey, List<OAuthProtocol.Scope>> scopes;
  @Getter public final List<ScopeKey> sortedScopesKeys;

  public final int fileUpdateMaxRetryCount;
  public final int fileUpdateMaxRetryIntervalMsec;
  public final String oauthHttpHeader;

  public final long httpConnectionTimeout;
  public final long httpReadTimeout;

  @Inject
  protected GitHubOAuthConfig(
      @GerritServerConfig Config config, CanonicalWebUrl canonicalWebUrl, AuthConfig authConfig) {
    this.config = config;
    this.canonicalWebUrl = canonicalWebUrl;

    httpHeader =
        Preconditions.checkNotNull(
            config.getString("auth", null, "httpHeader"),
            "HTTP Header for GitHub user must be provided");
    gitHubUrl =
        trimTrailingSlash(
            MoreObjects.firstNonNull(
                config.getString(CONF_SECTION, null, "url"), GITHUB_URL_DEFAULT));
    gitHubApiUrl =
        trimTrailingSlash(
            MoreObjects.firstNonNull(
                config.getString(CONF_SECTION, null, "apiUrl"), GITHUB_API_URL_DEFAULT));
    gitHubClientId =
        Preconditions.checkNotNull(
            config.getString(CONF_SECTION, null, "clientId"), "GitHub `clientId` must be provided");
    gitHubClientSecret =
        Preconditions.checkNotNull(
            config.getString(CONF_SECTION, null, "clientSecret"),
            "GitHub `clientSecret` must be provided");

    oauthHttpHeader = config.getString("auth", null, "httpExternalIdHeader");
    gitHubOAuthUrl = gitHubUrl + GITHUB_OAUTH_AUTHORIZE;
    gitHubOAuthAccessTokenUrl = gitHubUrl + GITHUB_OAUTH_ACCESS_TOKEN;
    logoutRedirectUrl = config.getString(CONF_SECTION, null, "logoutRedirectUrl");

    enabled = config.getString("auth", null, "type").equalsIgnoreCase(AuthType.HTTP.toString());
    scopes = getScopes(config);
    sortedScopesKeys =
        scopes
            .keySet()
            .stream()
            .sorted(Comparator.comparing(ScopeKey::getSequence))
            .collect(Collectors.toList());

    fileUpdateMaxRetryCount = config.getInt(CONF_SECTION, "fileUpdateMaxRetryCount", 3);
    fileUpdateMaxRetryIntervalMsec =
        config.getInt(CONF_SECTION, "fileUpdateMaxRetryIntervalMsec", 3000);

    httpConnectionTimeout =
        TimeUnit.MILLISECONDS.convert(
            ConfigUtil.getTimeUnit(
                config, CONF_SECTION, null, "httpConnectionTimeout", 30, TimeUnit.SECONDS),
            TimeUnit.SECONDS);

    httpReadTimeout =
        TimeUnit.MILLISECONDS.convert(
            ConfigUtil.getTimeUnit(
                config, CONF_SECTION, null, "httpReadTimeout", 30, TimeUnit.SECONDS),
            TimeUnit.SECONDS);
  }

  public String getOAuthFinalRedirectUrl(HttpServletRequest req) {
    return req == null
        ? GERRIT_OAUTH_FINAL
        : trimTrailingSlash(canonicalWebUrl.get(req)) + GERRIT_OAUTH_FINAL;
  }

  public String getScopeSelectionUrl(HttpServletRequest req) {
    String canonicalUrl = req == null ? "" : trimTrailingSlash(canonicalWebUrl.get(req));
    return canonicalUrl
        + MoreObjects.firstNonNull(
            config.getString(CONF_SECTION, null, "scopeSelectionUrl"), GITHUB_PLUGIN_OAUTH_SCOPE);
  }

  private Map<ScopeKey, List<Scope>> getScopes(Config config) {
    return config
        .getNames(CONF_SECTION, true)
        .stream()
        .filter(k -> k.startsWith("scopes"))
        .filter(k -> !k.endsWith("Description"))
        .filter(k -> !k.endsWith("Sequence"))
        .collect(
            Collectors.toMap(
                k ->
                    new ScopeKey(
                        k,
                        config.getString(CONF_SECTION, null, k + "Description"),
                        config.getInt(CONF_SECTION, k + "Sequence", 0)),
                v -> parseScopesString(config.getString(CONF_SECTION, null, v))));
  }

  private String trimTrailingSlash(String url) {
    return CharMatcher.is('/').trimTrailingFrom(url);
  }

  private List<Scope> parseScopesString(String scopesString) {
    ArrayList<Scope> result = new ArrayList<>();
    if (Strings.emptyToNull(scopesString) != null) {
      String[] scopesStrings = scopesString.split(",");
      for (String scope : scopesStrings) {
        result.add(Enum.valueOf(Scope.class, scope.trim()));
      }
    }

    return result;
  }

  public Scope[] getDefaultScopes() {
    if (scopes == null || scopes.get("scopes") == null) {
      return new Scope[0];
    }
    return scopes.get("scopes").toArray(new Scope[0]);
  }
}
