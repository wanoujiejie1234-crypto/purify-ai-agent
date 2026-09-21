package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.exception.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 解析令牌、把当前用户挂到请求上，并按注解做鉴权。
 *
 * <p><b>它是整个系统里唯一碰令牌的地方。</b>解析只做一遍、结果放在请求属性里，
 * 后面的所有环节（参数解析器、controller）都从那个属性取——而不是各自再解一次，
 * 那样每多一处就多一次「解出来不一样」的可能。
 *
 * <p><b>失败时抛异常，不自己写响应。</b>{@code preHandle} 抛出的异常会走
 * {@code HandlerExceptionResolver}，由 {@code GlobalExceptionHandler} 统一渲染成
 * 项目那个 {@code {code, message}} 响应体。这对 SSE 接口尤其关键：
 * 鉴权发生在响应提交之前，所以浏览器 {@code fetch} 看到的是一个干净的 401 JSON，
 * 而不是一个连上了却没有任何事件的破损流——后者在前端表现为「一直在转圈」。
 *
 * <p><b>为什么不用 ThreadLocal 存当前用户</b>：{@code BaseAgent.runStream} 里有
 * {@code subscribeOn(Schedulers.boundedElastic())}，智能体的工具循环跑在 Reactor 的
 * 调度线程上，在请求线程里 set 的 ThreadLocal 在那里不保证可见。而失败的表现是
 * 「画像悄悄写到了别人名下」——正是这种只在并发下出现、本地怎么点都点不出来的 bug。
 * 这个项目的 {@code AgentRun} 注释里已经为同一类问题定过调子：
 * 会变的状态跟着 per-run 的对象走，不藏在隐式上下文里。
 */
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 允许匿名访问的路径前缀。**只放这四个**，都要和 {@code AuthController} 里的映射对上。
     *
     * <p>写成前缀匹配而不是精确匹配：{@code /api/auth/password/reset} 这种多级路径
     * 用精确匹配要列两遍，容易漏。而这几个路径下没有别的东西，
     * 前缀匹配不会误放行——真有人以后在 {@code /api/auth/} 下面加了需要登录的接口，
     * 那个接口自己标 {@code @RequireLogin} 就会被拦下（下面的逻辑是「标了才拦」）。
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/code",
            "/api/auth/password/reset",
    };

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检不带 Authorization 头（浏览器规范如此），拦下它只会让所有跨域请求失败，
        // 而且失败信息指向的是「预检被拒」，看不出真正的原因
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        boolean publicPath = isPublic(request);

        // 先尽力解析。即使这个接口允许匿名，解出来的用户也要挂上去——
        // 将来有「登录了看到更多」的接口时，不用为此再加一遍解析逻辑
        LoginUser loginUser = resolveLoginUser(request, publicPath);
        if (loginUser != null) {
            request.setAttribute(LoginUser.ATTRIBUTE, loginUser);
        }

        if (publicPath || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 方法级注解优先于类级：类上标 @RequireLogin、某个方法上标 @RequireAdmin，
        // 这个方法就该按 admin 要求。反过来（类上 admin、方法上 login）等于给一个方法降权，
        // 那种写法应该显式改类注解，而不是靠方法上的注解去覆盖
        if (hasAnnotation(handlerMethod, RequireAdmin.class)) {
            requireAdmin(loginUser, request);
            return true;
        }
        if (hasAnnotation(handlerMethod, RequireLogin.class)) {
            requireLogin(loginUser);
        }
        return true;
    }

    /**
     * 从请求头里取令牌并校验。没有令牌返回 {@code null}（表示匿名）；
     * 有令牌但不合法时，公开路径上当匿名处理，其余情况抛 401。
     *
     * <p><b>公开路径必须容忍坏令牌，否则会出一个人再也登不进去的死局。</b>
     * 前端会把 localStorage 里的令牌挂在**每个**请求上，包括登录请求。
     * 一个已经过期（或者因为换了签名密钥而失效）的令牌如果让
     * {@code POST /api/auth/login} 直接 401，用户就卡住了：他正因为令牌失效
     * 才来登录的，而登录这个动作本身又被那个令牌挡住。清 localStorage 才能出来——
     * 一个普通用户不可能知道要做这件事。
     *
     * <p>前端那边也做了配合（401 时清掉令牌再跳登录页），所以这条分支在正常流程里
     * 走不到。但它是最后一道保险：一个坏令牌不该让公开接口变得不可用。
     */
    private LoginUser resolveLoginUser(HttpServletRequest request, boolean publicPath) {
        String token = JwtService.extractToken(request.getHeader("Authorization"));
        if (token == null) {
            return null;
        }
        try {
            return jwtService.parse(token);
        }
        catch (AuthException exception) {
            if (!publicPath) {
                throw exception;
            }
            // 公开接口：当这个头不存在。这正是「尽力解析」的意思——
            // 解出来更好，解不出来也不该妨碍任何人访问一个本来就不需要登录的接口
            log.debug("[Auth] 公开接口上带了无效令牌，按匿名处理：path={}", request.getRequestURI());
            return null;
        }
    }

    private static void requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw AuthException.unauthorized("error.auth.loginRequired");
        }
    }

    private static void requireAdmin(LoginUser loginUser, HttpServletRequest request) {
        if (loginUser == null) {
            throw AuthException.unauthorized("error.auth.loginRequired");
        }
        if (!loginUser.isAdmin()) {
            log.warn("[Auth] 权限不足：{} 尝试访问 {}", loginUser.describe(), request.getRequestURI());
            throw AuthException.forbidden("error.auth.adminOnly");
        }
    }

    /**
     * 找注解，方法级优先。
     *
     * <p>用 {@code AnnotatedElementUtils} 而不是 {@code getAnnotation}：
     * 前者能找到元注解和组合注解，后者只能找到直接标上去的那个。
     * 现在的注解没有组合，但 {@code getAnnotation} 在「把注解标在接口上、
     * 实现在类里」这类场景下会返回 null，而那是 Spring MVC 里很常见的写法。
     */
    private static boolean hasAnnotation(HandlerMethod handlerMethod, Class<? extends java.lang.annotation.Annotation> type) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), type)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), type);
    }

    private static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : PUBLIC_PATHS) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
