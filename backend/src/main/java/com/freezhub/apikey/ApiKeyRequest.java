package com.freezhub.apikey;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A name is required: an unnamed credential cannot be told apart when deciding what to revoke. */
public record ApiKeyRequest(@NotBlank @Size(max = 255) String name) {
}
