package com.storesync.shared.security;

import com.storesync.shared.config.StoreSyncProperties;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Turns the bearer token into a {@link Caller}. Roles come from configuration and the staff/terminal tables. */
@Component
public class CallerResolver implements HandlerMethodArgumentResolver {

    private final Set<String> admins;
    private final Principals principals;

    public CallerResolver(StoreSyncProperties props, Principals principals) {
        List<String> a = props.security().adminSubjects();
        this.admins = a == null ? Set.of() : Set.copyOf(a);
        this.principals = principals;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            throw new IllegalStateException("no authenticated subject on a /v1 request");
        }
        String subject = jwt.getSubject();
        var roles = EnumSet.noneOf(Role.class);
        if (admins.contains(subject)) {
            roles.add(Role.ADMIN);
            return new Caller(subject, roles, null, null);
        }
        var p = principals.lookup(subject);
        if (p.isEmpty()) {
            return new Caller(subject, roles, null, null);
        }
        roles.add(p.get().role());
        if (p.get().role() == Role.MANAGER) {
            roles.add(Role.CASHIER);
        }
        return new Caller(subject, roles, p.get().storeCode(), p.get().terminalId());
    }
}
