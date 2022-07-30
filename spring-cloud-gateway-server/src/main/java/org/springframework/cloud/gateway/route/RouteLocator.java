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

package org.springframework.cloud.gateway.route;

import reactor.core.publisher.Flux;

/**
 * @author Spencer Gibb
 */
// TODO: rename to Routes?
public interface RouteLocator {

	/**
	 * 获取路由
	 * 外部化配置定义 Route 使用的是 RouteDefinition 组件。同样的也有配套的 RouteDefinitionLocator 组件。
	 *
	 * Gateway 通过接口 RouteLocator 接口来获取路由配置，RouteLocator 有不同的实现，对应了不同的定义路由的方式。
	 *
	 * @return {@link Flux}<{@link Route}>
	 */
	Flux<Route> getRoutes();

}
