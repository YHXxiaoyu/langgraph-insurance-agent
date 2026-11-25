package com.insurance.biz.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.biz.domain.po.User;
import com.insurance.biz.mapper.UserMapper;
import com.insurance.biz.security.InternalTokenFilter;
import com.insurance.biz.security.JwtAuthenticationFilter;
import com.insurance.biz.security.LoginUser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, InternalProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserMapper userMapper) {
        return username -> {
            User user = userMapper.selectOne(
                    Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
            if (user == null) {
                throw new UsernameNotFoundException("用户不存在: " + username);
            }
            return new LoginUser(user);
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           InternalTokenFilter internalTokenFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE 结束后容器会做一次 ASYNC 分派，Spring Security 6 默认会对它
                        // 再走一遍鉴权。但 JWT 过滤器是 OncePerRequestFilter，异步分派时不执行，
                        // SecurityContext 是空的，于是被判定为拒绝，把已经发完的响应强行中断
                        // （前端表现为 network error）。原始请求已经鉴过权，这里直接放行。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // 产品目录是公开信息：首页未登录也要能浏览，
                        // 只有下单、咨询、会话这些才需要登录
                        .requestMatchers(HttpMethod.GET, "/api/v1/products").permitAll()
                        // 内部接口在这里放行，改由 InternalTokenFilter 把关；
                        // 不放行的话 JWT 过滤器会先把 Python 的请求 401 掉
                        .requestMatchers("/internal/**").permitAll()
                        // 接口文档页和相关静态资源，不需要登录
                        .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/favicon.ico").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录已过期\",\"data\":null}");
                }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
