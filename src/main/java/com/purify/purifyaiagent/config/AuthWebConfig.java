package com.purify.purifyaiagent.config;

import com.purify.purifyaiagent.auth.AuthInterceptor;
import com.purify.purifyaiagent.auth.LoginUserArgumentResolver;
import com.purify.purifyaiagent.auth.RequireAdmin;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 把鉴权拦截器和 {@code @CurrentUser} 参数解析器挂到 Spring MVC 上。
 *
 * <p><b>为什么单独一个类，而不是让 {@link AuthConfig} 直接实现 {@code WebMvcConfigurer}：
 * 拦截器必须是容器里那一个 Bean。</b>{@code addInterceptors(InterceptorRegistry)} 的签名是
 * Spring 定死的，没法让它多接一个参数去注入；在 {@code AuthConfig} 里就得写成
 * {@code new AuthInterceptor(null)} 或者把 {@code JwtService} 也塞进来自己拼——
 * 前者造出一个没有 {@code JwtService} 的拦截器（启动一切正常，第一次请求解析令牌时才 NPE，
 * 而且是从一个看起来完全无关的地方炸出来），后者则让「哪个 Bean 才是真正在用的那个」
 * 变得说不清楚。分成两个类，注入的就是唯一那个 Bean。
 *
 * <p>项目里已经有 {@code StaticResourceConfig} 也实现了 {@code WebMvcConfigurer}。
 * 多个实现类是 Spring MVC 支持的用法，它们会被依次调用，互不覆盖——
 * 一个管静态资源映射，一个管鉴权，分开反而更清楚。
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthWebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 拦截范围只有 {@code /api/**}，<b>这一点绝对不能放宽</b>。
     *
     * <p>前端的路由（{@code /slim}、{@code /knowledge}……）和 {@code /files/download/**}
     * 都不在这个前缀下：前者要转发到 {@code index.html}，后者是给用户下载文件用的静态资源。
     * 它们都不该带令牌，一旦被拦进去，表现是「前端页面整个打不开」或「下载链接全部失效」，
     * 而错误信息指向的是 401，看不出真正的原因是拦截范围写错了。
     *
     * <p>公开路径的白名单在 {@link AuthInterceptor} 里，不写成这里的
     * {@code excludePathPatterns}——分两处写的话，「哪些接口是公开的」这个问题
     * 就得翻两个文件才能回答，而漏看的那个方向恰恰是「某个接口悄悄变成了公开的」。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 排除掉错误页本身：它不在 /api 下，写上只是为了万一将来
                // 有人改成拦全部路径时，Spring Boot 的错误转发不会二次进拦截器
                .excludePathPatterns("/error");
    }

    /**
     * 让 {@code @CurrentUser} 在 controller 参数上生效。
     *
     * <p>见 {@link LoginUserArgumentResolver}——它读的是 {@link AuthInterceptor}
     * 挂在请求上的那个属性，所以这个解析器和上面那个拦截器是配套的，
     * 少了任何一个，{@code @CurrentUser} 都拿不到值。
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginUserArgumentResolver());
    }
}
