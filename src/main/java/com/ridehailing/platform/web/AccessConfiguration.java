package com.ridehailing.platform.web;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Caller;
import com.ridehailing.platform.security.Callers;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the access check on every API handler, and {@link Caller} as a handler parameter (LLD §12.4). */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessInterceptor());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerResolver());
    }

    private static final class CallerResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == Caller.class;
        }

        @Override
        public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                NativeWebRequest request, WebDataBinderFactory binders) {
            return Callers.current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "A valid access token is required."));
        }
    }
}
