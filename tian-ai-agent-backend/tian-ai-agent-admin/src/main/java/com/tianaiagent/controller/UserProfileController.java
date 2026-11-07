package com.tianaiagent.controller;

import com.tianaiagent.profile.UserProfileRepository;
import com.tianaiagent.profile.UserProfileRepository.ProfileUpdate;
import com.tianaiagent.profile.UserProfileRepository.UserProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users/{userId}/profile")
@ConditionalOnProperty(name = "agent.persistence.backend", havingValue = "mysql", matchIfMissing = true)
public class UserProfileController {
    private final UserProfileRepository profiles;
    public UserProfileController(UserProfileRepository profiles) { this.profiles = profiles; }

    @GetMapping
    public ResponseEntity<UserProfile> get(@PathVariable String userId) {
        return profiles.find(userId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** PUT fully replaces fields. Only submit facts explicitly provided/confirmed by the user. */
    @PutMapping
    public UserProfile put(@PathVariable String userId, @RequestBody ProfileUpdate update) {
        return profiles.replace(userId, update);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String userId) { profiles.delete(userId); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalid(IllegalArgumentException error) {
        return java.util.Map.of("error", error.getMessage());
    }
}
