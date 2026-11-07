package com.tianaiagent.user;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class CurrentUserService {
    private static final String USER_ATTRIBUTE = "tian.logged-in-user";
    private final UserRepository users;
    public CurrentUserService(UserRepository users) {
        this.users = users;
    }
    public UserRepository.UserView current(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(USER_ATTRIBUTE) instanceof String username) {
            return users.find(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    public String userId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("用户身份必须在HTTP入口解析并传给后台任务");
        }
        return current(attributes.getRequest()).userId();
    }
    public UserRepository.UserView login(HttpServletRequest request, String username, String password) {
        var user = users.authenticate(username, password)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        var old = request.getSession(false);
        if (old != null) old.invalidate();
        request.getSession(true).setAttribute(USER_ATTRIBUTE, user.username());
        return user;
    }

    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
