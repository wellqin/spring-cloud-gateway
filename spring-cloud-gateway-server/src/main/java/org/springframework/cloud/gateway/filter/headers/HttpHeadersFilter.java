/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.gateway.filter.headers;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

public interface HttpHeadersFilter {

	/**
	 * 过滤请求
	 *
	 * Java 8 新增了接口的默认方法。
	 * 简单说，默认方法就是接口可以有实现方法，而且不需要实现类去实现其方法。
	 * 我们只需在方法名前面加个 default 关键字即可实现默认方法。
	 *
	 * @param filters  过滤器
	 * @param exchange 交换
	 * @return {@link HttpHeaders}
	 */
	static HttpHeaders filterRequest(List<HttpHeadersFilter> filters, ServerWebExchange exchange) {
		HttpHeaders headers = exchange.getRequest().getHeaders();
		return filter(filters, headers, exchange, Type.REQUEST);
	}

	/**
	 * 过滤器
	 *
	 * @param filters  过滤器
	 * @param input    输入
	 * @param exchange 交换
	 * @param type     类型
	 * @return {@link HttpHeaders}
	 */
	static HttpHeaders filter(List<HttpHeadersFilter> filters, HttpHeaders input, ServerWebExchange exchange,
							  Type type) {
		if (filters != null) {
			HttpHeaders filtered = input;
			for (HttpHeadersFilter filter : filters) {
				if (filter.supports(type)) {
					filtered = filter.filter(filtered, exchange);
				}
			}
			return filtered;
		}

		return input;
	}

	/**
	 * Filters a set of Http Headers.
	 * @param input Http Headers
	 * @param exchange a {@link ServerWebExchange} that should be filtered
	 * @return filtered Http Headers
	 */
	HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange);

	/**
	 * 支持
	 *
	 * @param type 类型
	 * @return boolean
	 */
	default boolean supports(Type type) {
		return type.equals(Type.REQUEST);
	}

	enum Type {

		/**
		 * Filter for request headers.
		 */
		REQUEST,

		/**
		 * Filter for response headers.
		 */
		RESPONSE

	}

}
