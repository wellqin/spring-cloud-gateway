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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;

import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.HasRouteId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.ServerWebExchange;

/**
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}.
 *R outeLocator 最主要的实现类，用于将 RouteDefinition 转换成 Route。
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator implements RouteLocator {

	/**
	 * Default filters name.
	 */
	public static final String DEFAULT_FILTERS = "defaultFilters";

	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;

	private final ConfigurationService configurationService;

	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();

	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();

	/**
	 * 网关属性：外部化配置类
	 */
	private final GatewayProperties gatewayProperties;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
			List<RoutePredicateFactory> predicates, List<GatewayFilterFactory> gatewayFilterFactories,
			GatewayProperties gatewayProperties, ConfigurationService configurationService) {
		this.routeDefinitionLocator = routeDefinitionLocator;
		this.configurationService = configurationService;
		initFactories(predicates);
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named " + key + " already exists, class: "
						+ this.predicates.get(key) + ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	/**
	 * 获取路由
	 *
	 * @return {@link Flux}<{@link Route}>
	 */
	@Override
	public Flux<Route> getRoutes() {
		// 调用 convertToRoute 方法将 RouteDefinition 转换成 Route。
		Flux<Route> routes = this.routeDefinitionLocator.getRouteDefinitions().map(this::convertToRoute);

		if (!gatewayProperties.isFailOnRouteDefinitionError()) {
			// instead of letting error bubble up, continue
			routes = routes.onErrorContinue((error, obj) -> {
				if (logger.isWarnEnabled()) {
					logger.warn("RouteDefinition id " + ((RouteDefinition) obj).getId()
							+ " will be ignored. Definition has invalid configs, " + error.getMessage());
				}
			});
		}

		return routes.map(route -> {
			if (logger.isDebugEnabled()) {
				logger.debug("RouteDefinition matched: " + route.getId());
			}
			return route;
		});
	}

	private Route convertToRoute(RouteDefinition routeDefinition) {
		// 将 PredicateDefinition 转换成 AsyncPredicate
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		// 将 FilterDefinition 转换成 GatewayFilter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);
		// 生成 Route 对象
		return Route.async(routeDefinition).asyncPredicate(predicate).replaceFilters(gatewayFilters).build();
	}

	@SuppressWarnings("unchecked")
	List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		// 根据名称获取对应的 filter factory，生成 config 对象，绑定属性，调用工厂方法产生 GatewayFilter 对象。
		// 从过滤器工厂中取出配置了的过滤器封装到路由的过滤器集合中
		ArrayList<GatewayFilter> ordered = new ArrayList<>(filterDefinitions.size());
		for (int i = 0; i < filterDefinitions.size(); i++) {
			FilterDefinition definition = filterDefinitions.get(i);
			GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
			if (factory == null) {
				throw new IllegalArgumentException(
						"Unable to find GatewayFilterFactory with name " + definition.getName());
			}
			if (logger.isDebugEnabled()) {
				logger.debug("RouteDefinition " + id + " applying filter " + definition.getArgs() + " to "
						+ definition.getName());
			}

			// @formatter:off
			Object configuration = this.configurationService.with(factory)
					.name(definition.getName())
					.properties(definition.getArgs())
					.eventFunction((bound, properties) -> new FilterArgsEvent(
							// TODO: why explicit cast needed or java compile fails
							RouteDefinitionRouteLocator.this, id, (Map<String, Object>) properties))
					.bind();
			// @formatter:on

			// some filters require routeId
			// TODO: is there a better place to apply this?
			if (configuration instanceof HasRouteId) {
				HasRouteId hasRouteId = (HasRouteId) configuration;
				hasRouteId.setRouteId(id);
			}
			// 遍历过滤器集合，封装成带Order值的过滤器集合
			GatewayFilter gatewayFilter = factory.apply(configuration);
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				// 从1开始递增生成Order值
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}

	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		// TODO: support option to apply defaults after route specific filters?
		// ① 处理 GatewayProperties 中定义的默认的 FilterDefinition，转换成 全局GatewayFilter
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(),
					new ArrayList<>(this.gatewayProperties.getDefaultFilters())));
		}
		// ② 将 RouteDefinition 中定义的 FilterDefinition 转换成 GatewayFilter。
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), new ArrayList<>(routeDefinition.getFilters())));
		}
		// ③ 对 GatewayFilter 进行排序，排序的详细逻辑请查阅 spring 中的 Ordered 接口。
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		if (predicates == null || predicates.isEmpty()) {
			// this is a very rare case, but possible, just match all
			return AsyncPredicate.from(exchange -> true);
		}
		// 调用 lookup 方法，将列表中第一个 PredicateDefinition 转换成 AsyncPredicate
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));
		// 循环调用，将列表中每一个 PredicateDefinition 都转换成 AsyncPredicate
		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			// 应用 and 操作，将所有的 AsyncPredicate 组合成一个 AsyncPredicate 对象
			predicate = predicate.and(found);
		}

		return predicate;
	}

	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {
		// ① 根据 predicate 名称获取对应的 predicate factory。
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
			throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying " + predicate.getArgs() + " to "
					+ predicate.getName());
		}

		// @formatter:off
		Object config = this.configurationService.with(factory)
				.name(predicate.getName())
				.properties(predicate.getArgs())
				.eventFunction((bound, properties) -> new PredicateArgsEvent(
						RouteDefinitionRouteLocator.this, route.getId(), properties))
				.bind();
		// @formatter:on
		// ② 获取 PredicateDefinition 中的 Map 类型参数，key 是固定字符串_genkey_ + 数字拼接而成。
		// ③ 对第 ② 步获得的参数作进一步转换，key为 config 类（工厂类中通过范型指定）的属性名称。
		// ④ 调用 factory 的 newConfig 方法创建一个 config 类对象。
		// ⑤ 将第 ③ 步中产生的参数绑定到 config 对象上。
		// ⑥ 将 cofing 作参数代入，调用 factory 的 applyAsync 方法创建 AsyncPredicate 对象。
		return factory.applyAsync(config);
	}

}
