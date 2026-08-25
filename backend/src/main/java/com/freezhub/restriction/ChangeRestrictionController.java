package com.freezhub.restriction;

import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/restrictions")
public class ChangeRestrictionController {

    private final ChangeRestrictionService changeRestrictionService;

    public ChangeRestrictionController(ChangeRestrictionService changeRestrictionService) {
        this.changeRestrictionService = changeRestrictionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponse create(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @Valid @RequestBody CreateRestrictionRequest request) {
        ChangeRestriction created =
                changeRestrictionService.create(caller.organizationId(), caller.userId(), request);
        return RestrictionResponse.from(created);
    }

}
