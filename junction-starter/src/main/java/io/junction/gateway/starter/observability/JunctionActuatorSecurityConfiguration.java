package io.junction.gateway.starter.observability;

import io.junction.gateway.starter.JunctionProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class JunctionActuatorSecurityConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "junction.observability.security",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public ObservabilitySecurityExposureValidator observabilitySecurityExposureValidator(JunctionProperties properties,
                                                                                         Environment environment) {
        var validator = new ObservabilitySecurityExposureValidator(properties, environment);
        validator.validate();
        return validator;
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "junction.observability.security",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public UserDetailsService junctionActuatorUserDetailsService(JunctionProperties properties,
                                                                 ObservabilitySecurityExposureValidator validator) {
        var security = properties.getObservability().getSecurity();
        var username = StringUtils.hasText(security.getUsername()) ? security.getUsername() : "actuator";
        var encodedPassword = new BCryptPasswordEncoder().encode(security.getPassword());
        var user = User.withUsername(username)
            .password(encodedPassword)
            .roles("ACTUATOR")
            .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnProperty(
        prefix = "junction.observability.security",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public SecurityFilterChain junctionActuatorSecurityFilterChain(HttpSecurity http,
                                                                   UserDetailsService junctionActuatorUserDetailsService,
                                                                   JunctionProperties properties) throws Exception {
        var junctionActuatorAuthenticationProvider = new org.springframework.security.authentication.dao.DaoAuthenticationProvider(
            junctionActuatorUserDetailsService
        );
        junctionActuatorAuthenticationProvider.setPasswordEncoder(new BCryptPasswordEncoder());
        http.securityMatcher(EndpointRequest.toAnyEndpoint());
        http.authenticationProvider(junctionActuatorAuthenticationProvider);
        http.csrf(AbstractHttpConfigurer::disable);
        http.httpBasic(Customizer.withDefaults());
        http.authorizeHttpRequests(authorize -> {
            if (properties.getObservability().getSecurity().isPublicHealthEnabled()) {
                authorize.requestMatchers(EndpointRequest.to("health")).permitAll();
            }
            authorize.anyRequest().authenticated();
        });
        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    @ConditionalOnProperty(prefix = "junction.observability.security", name = "enabled", havingValue = "false")
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain junctionPermitAllSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        http.csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
