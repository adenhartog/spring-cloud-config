/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.server.environment;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.apache.HttpClientConnection;
import org.springframework.cloud.config.server.support.HttpClient4Support;

import static java.util.stream.Collectors.toMap;

/**
 * @author Dylan Roberts
 */
public class HttpClientConfigurableHttpConnectionFactory implements ConfigurableHttpConnectionFactory {

	private static final String PLACEHOLDER_PATTERN_STRING = "\\{(\\w+)}";

	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(PLACEHOLDER_PATTERN_STRING);

	Log log = LogFactory.getLog(getClass());

	Map<String, HttpClientBuilder> httpClientBuildersByUri = new LinkedHashMap<>();

	@Override
	public void addConfiguration(MultipleJGitEnvironmentProperties environmentProperties)
			throws GeneralSecurityException {
		addHttpClient(environmentProperties);
		for (JGitEnvironmentProperties repo : environmentProperties.getRepos().values()) {
			addHttpClient(repo);
		}
	}

	@Override
	public HttpConnection create(URL url) throws IOException {
		return create(url, null);
	}

	@Override
	public HttpConnection create(URL url, Proxy proxy) throws IOException {
		HttpClientBuilder builder = lookupHttpClientBuilder(url);
		if (builder != null) {
			return new HttpClientConnection(url.toString(), null, builder.build());
		}
		else {
			/*
			 * No matching builder found: let jGit handle the creation of the HttpClient
			 */
			return new HttpClientConnection(url.toString());
		}
	}

	private void addHttpClient(JGitEnvironmentProperties properties) throws GeneralSecurityException {
		if (properties.getUri() != null && properties.getUri().startsWith("http")) {
			this.httpClientBuildersByUri.put(properties.getUri(), HttpClient4Support.builder(properties));
		}
	}

	private HttpClientBuilder lookupHttpClientBuilder(final URL url) {
		Map<String, HttpClientBuilder> builderMap = this.httpClientBuildersByUri.entrySet().stream().filter(entry -> {
			var uriPattern = UriTemplate.parse(entry.getKey());
			return uriPattern.matcher(url.toString()).matches();
		}).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

		if (builderMap.isEmpty()) {
			this.log.warn(String.format("No custom http config found for URL: %s", url));
			return null;
		}
		if (builderMap.size() > 1) {
			/*
			 * Try to determine if there is an exact match URL or not. So if there is a
			 * placeholder in the URL, filter it out. We should be left with only URLs
			 * which have no placeholders. That is the one we want to use in the case
			 * there are multiple matches.
			 */
			List<String> keys = builderMap.keySet().stream().filter(key -> !PLACEHOLDER_PATTERN.matcher(key).find())
					.collect(Collectors.toList());

			if (keys.size() == 1) {
				return builderMap.get(keys.get(0));
			}
			this.log.error(String.format(
					"More than one git repo URL template matched URL:"
							+ " %s, proxy and skipSslValidation config won't be applied. Matched templates: %s",
					url, builderMap.keySet().stream().collect(Collectors.joining(", "))));
			return null;
		}
		return new ArrayList<>(builderMap.values()).get(0);
	}

	// Extract from: org.springframework.web.util.UriTemplate
	private static final class UriTemplate {

		private UriTemplate() {
		}

		public static Pattern parse(String uriTemplate) {
			int level = 0;
			StringBuilder pattern = new StringBuilder();
			StringBuilder builder = new StringBuilder();

			for (int i = 0; i < uriTemplate.length(); ++i) {
				char c = uriTemplate.charAt(i);
				if (c == '{') {
					++level;
					if (level == 1) {
						pattern.append(quote(builder));
						builder = new StringBuilder();
						continue;
					}
				} else if (c == '}') {
					--level;
					if (level == 0) {
						String variable = builder.toString();
						int idx = variable.indexOf(58);
						if (idx == -1) {
							pattern.append("([^/]*)");
						} else {
							if (idx + 1 == variable.length()) {
								throw new IllegalArgumentException("No custom regular expression specified after ':' in \"" + variable + "\"");
							}

							String regex = variable.substring(idx + 1);
							pattern.append('(');
							pattern.append(regex);
							pattern.append(')');
						}

						builder = new StringBuilder();
						continue;
					}
				}

				builder.append(c);
			}

			if (builder.length() > 0) {
				pattern.append(quote(builder));
			}

			// trailing path or query string should not be relevant
			pattern.append(".*");

			return Pattern.compile(pattern.toString());
		}

		private static String quote(StringBuilder builder) {
			return builder.length() > 0 ? Pattern.quote(builder.toString()) : "";
		}
	}
}


