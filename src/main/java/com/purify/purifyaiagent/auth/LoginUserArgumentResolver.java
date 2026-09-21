package com.purify.purifyaiagent.auth;

import com.purify.purifyaiagent.exception.AuthException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 {@link AuthInterceptor} 挂在请求属性上的当前用户，交给标注了
 * {@link CurrentUser} 的 controller 参数。
 *
 * <p>薄薄一层转换，存在的理由是把「取当前用户」这件事<b>只定义一次</b>。
 * 不这么做的话，每个需要用户的 controller 方法都要写一行
 * {@code (LoginUser) request.getAttribute(LoginUser.ATTRIBUTE)}，而那一行有两个
 * 各自能出错的地方：属性名（写成字面量就会拼错）和类型转换。
 * 这两个错误都不会在编译期被发现，表现都是「拿到 null」。
 */
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && LoginUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Object attribute = webRequest.getAttribute(LoginUser.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (attribute instanceof LoginUser loginUser) {
            return loginUser;
        }

        // @CurrentUser 默认 required = true，所以取不到就是真的出了问题
        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
        if (annotation != null && !annotation.required()) {
            return null;
        }

        // 最可能的原因是这个接口忘了标 @RequireLogin——鉴权拦截器没解析令牌，
        // 属性自然就是空的。把这句话写进异常里，比让一个 null 顺着代码流下去
        // 最后在某处变成 NPE 要好查得多
        throw AuthException.unauthorized("error.auth.missingCurrentUser", parameter.getMethod());
    }
}
