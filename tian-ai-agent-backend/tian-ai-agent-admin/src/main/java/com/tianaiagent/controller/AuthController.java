package com.tianaiagent.controller;

import com.tianaiagent.user.CurrentUserService;
import com.tianaiagent.user.UserRepository.UserView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class AuthController {
    private final CurrentUserService users;
    public AuthController(CurrentUserService users) { this.users = users; }
    @PostMapping("/login")
    public UserView login(@RequestBody LoginRequest body, HttpServletRequest request) {
        return users.login(request, body.username(), body.password());
    }
    @GetMapping("/me")
    public UserView me(HttpServletRequest request) { return users.current(request); }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) { users.logout(request); }
    public record LoginRequest(String username, String password) {}
}
